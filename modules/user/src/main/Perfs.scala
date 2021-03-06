package lila.user

import reactivemongo.bson.BSONDocument

import chess.Speed
import lila.db.BSON
import lila.rating.{ Perf, PerfType, Glicko }

case class Perfs(
    standard: Perf,
    chess960: Perf,
    kingOfTheHill: Perf,
    threeCheck: Perf,
    antichess: Perf,
    atomic: Perf,
    horde: Perf,
    bullet: Perf,
    blitz: Perf,
    classical: Perf,
    correspondence: Perf,
    puzzle: Perf,
    opening: Perf) {

  def perfs = List(
    "standard" -> standard,
    "chess960" -> chess960,
    "kingOfTheHill" -> kingOfTheHill,
    "threeCheck" -> threeCheck,
    "antichess" -> antichess,
    "atomic" -> atomic,
    "horde" -> horde,
    "bullet" -> bullet,
    "blitz" -> blitz,
    "classical" -> classical,
    "correspondence" -> correspondence,
    "puzzle" -> puzzle,
    "opening" -> opening)

  def bestPerf: Option[(PerfType, Perf)] = {
    val ps = PerfType.nonPuzzle map { pt => pt -> apply(pt) }
    val minNb = math.max(1, ps.foldLeft(0)(_ + _._2.nb) / 10)
    ps.foldLeft(none[(PerfType, Perf)]) {
      case (ro, p) if p._2.nb >= minNb => ro.fold(p.some) { r =>
        Some(if (p._2.intRating > r._2.intRating) p else r)
      }
      case (ro, _) => ro
    }
  }

  def bestRating: Int = bestRatingIn(PerfType.leaderboardable)

  def bestRatingIn(types: List[PerfType]): Int = {
    val ps = types map apply match {
      case Nil => List(standard)
      case x   => x
    }
    val minNb = ps.foldLeft(0)(_ + _.nb) / 10
    ps.foldLeft(none[Int]) {
      case (ro, p) if p.nb >= minNb => ro.fold(p.intRating.some) { r =>
        Some(if (p.intRating > r) p.intRating else r)
      }
      case (ro, _) => ro
    } | Perf.default.intRating
  }

  def bestProgress: Int = bestProgressIn(PerfType.leaderboardable)

  def bestProgressIn(types: List[PerfType]): Int = types map apply match {
    case Nil   => 0
    case perfs => perfs.map(_.progress).max
  }

  lazy val perfsMap: Map[String, Perf] = Map(
    "chess960" -> chess960,
    "kingOfTheHill" -> kingOfTheHill,
    "threeCheck" -> threeCheck,
    "antichess" -> antichess,
    "atomic" -> atomic,
    "horde" -> horde,
    "bullet" -> bullet,
    "blitz" -> blitz,
    "classical" -> classical,
    "correspondence" -> correspondence,
    "puzzle" -> puzzle,
    "opening" -> opening)

  def ratingMap: Map[String, Int] = perfsMap mapValues (_.intRating)

  def ratingOf(pt: String): Option[Int] = perfsMap get pt map (_.intRating)

  def apply(key: String): Option[Perf] = perfsMap get key

  def apply(perfType: PerfType): Perf = perfType match {
    case PerfType.Standard       => standard
    case PerfType.Bullet         => bullet
    case PerfType.Blitz          => blitz
    case PerfType.Classical      => classical
    case PerfType.Correspondence => correspondence
    case PerfType.Chess960       => chess960
    case PerfType.KingOfTheHill  => kingOfTheHill
    case PerfType.ThreeCheck     => threeCheck
    case PerfType.Antichess      => antichess
    case PerfType.Atomic         => atomic
    case PerfType.Horde          => horde
    case PerfType.Puzzle         => puzzle
    case PerfType.Opening        => opening
  }

  def inShort = perfs map {
    case (name, perf) => s"$name:${perf.intRating}"
  } mkString ", "

  def updateStandard = copy(
    standard = {
      val subs = List(bullet, blitz, classical, correspondence)
      subs.maxBy(_.latest.fold(0l)(_.getMillis)).latest.fold(standard) { date =>
        val nb = subs.map(_.nb).sum
        val glicko = Glicko(
          rating = subs.map(s => s.glicko.rating * (s.nb / nb.toDouble)).sum,
          deviation = subs.map(s => s.glicko.deviation * (s.nb / nb.toDouble)).sum,
          volatility = subs.map(s => s.glicko.volatility * (s.nb / nb.toDouble)).sum)
        Perf(
          glicko = glicko,
          nb = nb,
          recent = Nil,
          latest = date.some)
      }
    }
  )
}

case object Perfs {

  val default = {
    val p = Perf.default
    Perfs(p, p, p, p, p, p, p, p, p, p, p, p, p)
  }

  def variantLens(variant: chess.variant.Variant): Option[Perfs => Perf] = variant match {
    case chess.variant.Standard      => Some(_.standard)
    case chess.variant.Chess960      => Some(_.chess960)
    case chess.variant.KingOfTheHill => Some(_.kingOfTheHill)
    case chess.variant.ThreeCheck    => Some(_.threeCheck)
    case chess.variant.Antichess     => Some(_.antichess)
    case chess.variant.Atomic        => Some(_.atomic)
    case chess.variant.Horde         => Some(_.horde)
    case _                           => none
  }

  def speedLens(speed: Speed): Perfs => Perf = speed match {
    case Speed.Bullet => perfs => perfs.bullet
    case Speed.Blitz => perfs => perfs.blitz
    case Speed.Classical => perfs => perfs.classical
    case Speed.Correspondence => perfs => perfs.correspondence
  }

  val perfsBSONHandler = new BSON[Perfs] {

    implicit def perfHandler = Perf.perfBSONHandler
    import BSON.MapDocument._

    def reads(r: BSON.Reader): Perfs = {
      def perf(key: String) = r.getO[Perf](key) getOrElse Perf.default
      Perfs(
        standard = perf("standard"),
        chess960 = perf("chess960"),
        kingOfTheHill = perf("kingOfTheHill"),
        threeCheck = perf("threeCheck"),
        antichess = perf("antichess"),
        atomic = perf("atomic"),
        horde = perf("horde"),
        bullet = perf("bullet"),
        blitz = perf("blitz"),
        classical = perf("classical"),
        correspondence = perf("correspondence"),
        puzzle = perf("puzzle"),
        opening = perf("opening"))
    }

    private def notNew(p: Perf): Option[Perf] = p.nb > 0 option p

    def writes(w: BSON.Writer, o: Perfs) = BSONDocument(
      "standard" -> notNew(o.standard),
      "chess960" -> notNew(o.chess960),
      "kingOfTheHill" -> notNew(o.kingOfTheHill),
      "threeCheck" -> notNew(o.threeCheck),
      "antichess" -> notNew(o.antichess),
      "atomic" -> notNew(o.atomic),
      "horde" -> notNew(o.horde),
      "bullet" -> notNew(o.bullet),
      "blitz" -> notNew(o.blitz),
      "classical" -> notNew(o.classical),
      "correspondence" -> notNew(o.correspondence),
      "puzzle" -> notNew(o.puzzle),
      "opening" -> notNew(o.opening))
  }
}
