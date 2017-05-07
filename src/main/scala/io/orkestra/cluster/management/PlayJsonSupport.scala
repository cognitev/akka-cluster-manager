package io.orkestra.cluster.management

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

/**
 * A trait providing automatic to and from JSON marshalling/unmarshalling using an in-scope *play-json* protocol.
 */
trait PlayJsonSupport {

  type Printer = (JsValue ⇒ String)

  def read[T](jsValue: JsValue)(implicit reads: Reads[T]): T = {
    reads.reads(jsValue) match {
      case s: JsSuccess[T] ⇒ s.get
      case e: JsError ⇒ throw JsResultException(e.errors)
    }
  }

  implicit def playJsonUnmarshallerConverter[T](reads: Reads[T])(implicit ec: ExecutionContext, mat: Materializer): FromEntityUnmarshaller[T] =
    playJsonUnmarshaller(reads, ec, mat)

  implicit def playJsonUnmarshaller[T](implicit reads: Reads[T], ec: ExecutionContext, mat: Materializer): FromEntityUnmarshaller[T] =
    playJsValueUnmarshaller.map(read[T])

  implicit def playJsValueUnmarshaller(implicit ec: ExecutionContext, mat: Materializer): FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) ⇒
      if (charset == HttpCharsets.`UTF-8`) Json.parse(data.toArray)
      else Json.parse(data.decodeString(charset.nioCharset.name)) // FIXME: identify charset by instance, not by name!
    }

  implicit def playJsonMarshallerConverter[T](writes: Writes[T])(implicit printer: Printer = Json.prettyPrint, ec: ExecutionContext): ToEntityMarshaller[T] =
    playJsonMarshaller[T](writes, printer, ec)

  implicit def playJsonMarshaller[T](implicit writes: Writes[T], printer: Printer = Json.prettyPrint, ec: ExecutionContext): ToEntityMarshaller[T] =
    playJsValueMarshaller[T].compose(writes.writes)

  implicit def playJsValueMarshaller[T](implicit writes: Writes[T], printer: Printer = Json.prettyPrint, ec: ExecutionContext): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(`application/json`)(printer)
}

object PlayJsonSupport extends PlayJsonSupport
