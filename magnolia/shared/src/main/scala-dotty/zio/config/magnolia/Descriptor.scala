package zio.config.magnolia

import zio.config._, ConfigDescriptor._
import zio.duration.Duration
import zio.config.derivation.DerivationUtils

import java.io.File
import java.net.{URI, URL}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID
import scala.concurrent.duration.{Duration => ScalaDuration}
import scala.deriving._
import scala.compiletime.{erasedValue, summonInline, constValue}
import scala.quoted
import scala.util.Try

final case class Descriptor[A](desc: ConfigDescriptor[A])

object Descriptor {
  def apply[A](implicit ev: Descriptor[A]) =
    ev.desc

  lazy given Descriptor[String] = Descriptor(string)
  lazy given Descriptor[Boolean] = Descriptor(boolean)
  lazy given Descriptor[Byte] = Descriptor(byte)
  lazy given Descriptor[Short] = Descriptor(short)
  lazy given Descriptor[Int] = Descriptor(int)
  lazy given Descriptor[Long] = Descriptor(long)
  lazy given Descriptor[BigInt] = Descriptor(bigInt)
  lazy given Descriptor[Float] = Descriptor(float)
  lazy given Descriptor[Double] = Descriptor(double)
  lazy given Descriptor[BigDecimal] = Descriptor(bigDecimal)
  lazy given Descriptor[URI] = Descriptor(uri)
  lazy given Descriptor[URL] = Descriptor(url)
  lazy given Descriptor[ScalaDuration] = Descriptor(duration)
  lazy given Descriptor[Duration] = Descriptor(zioDuration)
  lazy given Descriptor[UUID] = Descriptor(uuid)
  lazy given Descriptor[LocalDate] = Descriptor(localDate)
  lazy given Descriptor[LocalTime] = Descriptor(localTime)
  lazy given Descriptor[LocalDateTime] = Descriptor(localDateTime)
  lazy given Descriptor[Instant] = Descriptor(instant)
  lazy given Descriptor[File] = Descriptor(file)
  lazy given Descriptor[java.nio.file.Path] = Descriptor(javaFilePath)

  given eitherDesc[A, B](using ev1: Descriptor[A], ev2: Descriptor[B]): Descriptor[Either[A, B]] =
    Descriptor(ev1.desc.orElseEither(ev2.desc))

  given optDesc[A: Descriptor]: Descriptor[Option[A]] =
    Descriptor(Descriptor[A].optional)

  given listDesc[A](using ev: Descriptor[A]): Descriptor[List[A]] =
    Descriptor(list(ev.desc))

  given mapDesc[A](using ev: Descriptor[A]): Descriptor[Map[String, A]] =
    Descriptor(map(ev.desc))

  inline def summonDescriptorForCoProduct[T <: Tuple]: List[Descriptor[Any]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        Descriptor[Any](
          summonInline[Descriptor[t]].desc.transformOrFail[Any](
            a => Right(a),
            b => Try(castTo[t](b))
              .toEither
              .swap
              .map(r => s"Failed to write ${b}. ${r}")
              .swap
          )
        ) :: summonDescriptorForCoProduct[ts]

  inline def summonDescriptorAll[T <: Tuple]: List[Descriptor[_]] =
    inline erasedValue[T] match
      case _ : EmptyTuple => Nil
      case _: (t *: ts) => summonInline[Descriptor[t]] :: summonDescriptorAll[ts]

  inline def labelsToList[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _ : ( t *: ts) => constValue[t].toString :: labelsToList[ts]

  inline given derived[T](using m: Mirror.Of[T]): Descriptor[T] =
    inline m match
      case s: Mirror.SumOf[T] =>
        sum[T](using s)

      case p: Mirror.ProductOf[T] =>
        product[T](using p)

  inline def sum[T](using m: Mirror.SumOf[T]) =
    val nameOfT =
      constValue[m.MirroredLabel]

    val alternateNamesOfEnum: List[String] =
      Macros.nameAnnotations[T].map(_.name) ++ Macros.namesAnnotations[T].flatMap(_.names)

    val subClassNames =
      labelsToList[m.MirroredElemLabels]

    val subClassDescriptions =
      summonDescriptorForCoProduct[m.MirroredElemTypes]

    mergeAllProducts(
      subClassDescriptions.map(castTo[Descriptor[T]]),
      subClassNames
    )

  def mergeAllProducts[T](
    allDescs: => List[Descriptor[T]],
    subClassNames: List[String]
  ): Descriptor[T] =
    Descriptor(allDescs.zip(subClassNames).map({case (d, n) => nested(n)(d.desc)}).reduce(_ orElse _))

  inline def product[T](using m: Mirror.ProductOf[T]) =
    val nameOfT =
      constValue[m.MirroredLabel]

    val fieldNames =
      labelsToList[m.MirroredElemLabels]

    mergeAllFields(
      summonDescriptorAll[m.MirroredElemTypes],
      fieldNames,
      List(nameOfT),
      lst => m.fromProduct(Tuple.fromArray(lst.toArray[Any])),
      castTo[Product](_).productIterator.toList
    )

  def mergeAllFields[T](
     allDescs: => List[Descriptor[_]],
     fieldNames: => List[String],
     namesOfClass: => List[String],
     f: List[Any] => T,
     g: T => List[Any],
   ): Descriptor[T] =
       if fieldNames.isEmpty then
         val tryAllPaths = namesOfClass.map(n => DerivationUtils.constantString(n)).reduce(_ orElse _)
         Descriptor(tryAllPaths.transform[T](_ => f(List.empty[Any]), _.toString))
       else
         val listOfDesc =
           fieldNames.zip(allDescs).map({ case (a, b) => nested(a)(castTo[ConfigDescriptor[Any]](b.desc)) })

         val descOfList =
           collectAll(listOfDesc.head, listOfDesc.tail: _*)

         Descriptor(descOfList.transform[T](f, g))

  def castTo[T](a: Any): T =
    a.asInstanceOf[T]
}
