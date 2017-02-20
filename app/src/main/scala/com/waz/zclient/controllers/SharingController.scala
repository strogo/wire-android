/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.controllers

import android.app.Activity
import android.content.DialogInterface
import android.net.Uri
import android.support.v7.app.AlertDialog
import android.text.format.Formatter
import com.waz.ZLog._
import com.waz.api._
import com.waz.model.ConvId
import com.waz.service.ZMessaging
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.zclient.controllers.SharingController.{FileContent, ImageContent, SharableContent, TextContent}
import com.waz.zclient.utils.{IntentUtils, ViewUtils}
import com.waz.zclient.{Injectable, Injector, R}

import scala.collection.JavaConverters._
import scala.concurrent.Future

class SharingController(implicit injector: Injector, eventContext: EventContext) extends Injectable{
  import com.waz.threading.Threading.Implicits.Ui
  private implicit val tag: LogTag = logTagFor[SharingController]

  private lazy val context = inject[Activity]
  private lazy val zms = inject[Signal[ZMessaging]]

  val sharableContent = Signal[Option[SharableContent]](None)
  val ephemeralExpiration = Signal[EphemeralExpiration](EphemeralExpiration.NONE)

  val sendEvent = EventStream[(SharableContent, Set[ConvId], EphemeralExpiration)]()

  sendEvent{
    case (TextContent(content), conversations, expiration) =>
      zms.head.flatMap(z => Future.traverse(conversations){ convId =>
        z.convsUi.setEphemeral(convId, expiration).flatMap(_ =>
          z.convsUi.sendMessage(convId, new MessageContent.Text(content))) })
    case (FileContent(assetUris), conversations, expiration) =>
      for {
        conv <- conversations
        uri <- assetUris
      } yield zms.head.flatMap(z =>
        z.convsUi.setEphemeral(conv, expiration).flatMap(_ =>
          z.convsUi.sendMessage(conv, new MessageContent.Asset(AssetFactory.fromContentUri(uri), assetErrorHandler(context)))))
    case _ =>
  }

  private def assetErrorHandler(activity: Activity): MessageContent.Asset.ErrorHandler = new MessageContent.Asset.ErrorHandler() {
    def noWifiAndFileIsLarge(sizeInBytes: Long, net: NetworkMode, answer: MessageContent.Asset.Answer): Unit = {
      if (activity == null) {
        answer.ok()
        return
      }
      val dialog: AlertDialog = ViewUtils.showAlertDialog(activity, R.string.asset_upload_warning__large_file__title, R.string.asset_upload_warning__large_file__message_default, R.string.asset_upload_warning__large_file__button_accept, R.string.asset_upload_warning__large_file__button_cancel, new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, which: Int): Unit = {
          answer.ok()
        }
      }, new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, which: Int): Unit = {
          answer.cancel()
        }
      })
      dialog.setCancelable(false)
      if (sizeInBytes > 0) {
        val fileSize: String = Formatter.formatFileSize(activity, sizeInBytes)
        dialog.setMessage(activity.getString(R.string.asset_upload_warning__large_file__message, fileSize))
      }
    }
  }

  def onContentShared(activity: Activity, convIds: Set[ConvId]): Unit ={
    for{
      Some(content) <- sharableContent.currentValue
      expiration <- ephemeralExpiration.currentValue
    } yield {
      val javaConvIds = convIds.toSeq.map(_.str).asJava
      content match {
        case TextContent(text) =>
          activity.startActivity(IntentUtils.getAppLaunchIntent(activity, javaConvIds, text, expiration))
        case ImageContent(uris) =>
          activity.startActivity(IntentUtils.getAppLaunchIntent(activity, javaConvIds, uris.asJava, expiration))
        case FileContent(uris) =>
          activity.startActivity(IntentUtils.getAppLaunchIntent(activity, javaConvIds, uris.asJava, expiration))
        case _ =>
      }
    }
  }

  def clearSharableContent(): Unit ={
    sharableContent ! None
    ephemeralExpiration ! EphemeralExpiration.NONE
  }

  //java helpers
  def sendContent(text: String, uris: java.util.List[Uri], conversations: java.util.List[String], ephemeralExpiration: EphemeralExpiration, activity: Activity): Unit ={
    val convIds = conversations.asScala.filter(_ != null).map(ConvId(_)).toSet
    (Option(text), Option(uris)) match {
      case (Some(t), _) =>
        sendEvent ! (TextContent(t), convIds, ephemeralExpiration)
      case (_, Some(u)) if !u.isEmpty =>
        sendEvent ! (FileContent(uris.asScala), convIds, ephemeralExpiration)
      case _ =>
    }
  }

  def publishTextContent(text: String): Unit ={
    this.sharableContent ! Some(TextContent(text))
  }

  def publishImageContent(uris: java.util.List[Uri]): Unit ={
    this.sharableContent ! Some(ImageContent(uris.asScala))
  }

  def publishFileContent(uris: java.util.List[Uri]): Unit ={
    this.sharableContent ! Some(FileContent(uris.asScala))
  }
}

object SharingController {
  trait SharableContent

  case class TextContent(text: String) extends SharableContent

  case class FileContent(uris: Seq[Uri]) extends SharableContent

  case class ImageContent(uris: Seq[Uri]) extends SharableContent
}
