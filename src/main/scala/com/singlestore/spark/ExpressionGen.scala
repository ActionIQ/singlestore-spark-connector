package com.singlestore.spark

import org.apache.spark.DataSourceTelemetryHelpers
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

import scala.reflect.ClassTag

object ExpressionGen extends LazyLogging with DataSourceTelemetryHelpers {
  import SQLGen._

  final val SINGLESTORE_DECIMAL_MAX_PRECISION       = 65
  final val SINGLESTORE_DECIMAL_MAX_SCALE           = 30
  final val SINGLESTORE_DEFAULT_TIME_FORMAT         = UTF8String.fromString("yyyy-MM-dd HH:mm:ss")
  final val SINGLESTORE_DEFAULT_TIME_FORMAT_LITERAL = Literal(SINGLESTORE_DEFAULT_TIME_FORMAT, StringType)

  // DAYS_OF_WEEK_OFFSET_MAP is a map from week day prefix to it's offset (sunday -> 1, saturday -> 7)
  final val DAYS_OF_WEEK_OFFSET_MAP: Map[String, String] = {
    val daysOfWeek =
      List("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    val prefix2    = daysOfWeek.map(day => day.slice(0, 2))
    val prefix3    = daysOfWeek.map(day => day.slice(0, 3))
    val dayFormats = daysOfWeek ::: prefix3 ::: prefix2

    dayFormats.zipWithIndex.map { case (day, ind) => (day, (ind % 7 + 1).toString) }.toMap
  }

  /**
   * Function to convert a Spark date format to a SingleStore date format symbols
   *
   * Spark -> https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html
   * SingleStore -> https://docs.singlestore.com/cloud/reference/sql-reference/date-and-time-functions/date-format/
   */
  private final def sparkDateFmtToSingleStoreFmtSymbols(format: UTF8String): String = {
    val translatedDateFmt = format.toString match {
      case "M" => "%c"
      case "d" => "%e"
      case "H" => "%k"
      case "h" => "%l"
      case fmt =>
        // Note: Order of string replacement for groups matters
        fmt
          // SingleStore Time, 24-hour (hh:mm:ss)
          .replaceAll("HH:mm:ss", "%T")

          // SingleStore Time, 12-hour (hh:mm:ss followed by AM or PM)
          .replaceAll("hh:mm:ss a", "%r")

          // SingleStore Year, numeric, four digits
          .replaceAll("Y{4,}|y{4,}", "%Y")
          // SingleStore Year, numeric (two digits)
          .replaceAll("Y{2}|y{2}", "%y")

          // SingleStore Day of year (001 to 366)
          .replaceAll("D{1,3}", "%j")

          // SingleStore Month name (January to December)
          .replaceAll("M{4,}", "%M")
          // SingleStore Abbreviated month (Jan, Feb, Mar…)
          .replaceAll("M{3,}", "%b")
          // SingleStore Month number, padded (00 to 12)
          .replaceAll("M{2,}", "%m")
          // SingleStore Month number (0 to 12)
          .replaceAll("(?<=[^%])M+", "%c")

          // SingleStore Day of the month, padded (00 to 31)
          .replaceAll("d{3,}", "%d")
          .replaceAll("d{2,}", "%d")
          // SingleStore Day of the month (0 to 31)
          .replaceAll("(?<=[^%])d+", "%e")

          // SingleStore Hour of day, padded 24h format (00 to 23)
          .replaceAll("H{3,}", "%H")
          .replaceAll("H{2,}", "%H")
          // SingleStore Hour of day, 24h format (0 to 23)
          .replaceAll("(?<=[^%])H+", "%k")

          // SingleStore Hour of day, padded 12h format (01 to 12)
          .replaceAll("h{3,}", "%h")
          .replaceAll("h{2,}", "%h")
          // SingleStore Hour of day, 12h format (1 to 12)
          .replaceAll("(?<=[^%])h+", "%l")

          // SingleStore Minute of hour (00 to 59)
          .replaceAll("m{2,}", "%i")

          // SingleStore Seconds (00 to 59)
          .replaceAll("s+", "%s")

          // SingleStore Microseconds (000000 to 999999)
          .replaceAll("S+", "%f")

          // SingleStore AM or PM
          .replaceAll("a", "%p")

          // SingleStore Week (01 to 53) starting Monday
          .replaceAll("w+", "%v")

          // NOTE:
          //  Spark:       select from_unixtime(0, 'EE') -> Wed
          //  SingleStore: select FROM_UNIXTIME(0, '%a') -> Thu
          //
          // SingleStore Weekday name (Sunday to Saturday)
          .replaceAll("E{4,}", "%W")
          // SingleStore Abbreviated day of week (Sun, Mon, Tue, etc)
          .replaceAll("E{1,3}", "%a")
    }

    log.info(
      logEventNameTagger(
        s"Translated Spark Date Format: `${format.toString}` " +
          s"to SingleStore Date Format Symbols: `$translatedDateFmt`."
      )
    )

    translatedDateFmt
  }

  /**
   * Function to convert a Spark date format to a SingleStore date format specifiers
   *
   * Spark -> https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html
   * SingleStore -> https://docs.singlestore.com/cloud/reference/sql-reference/date-and-time-functions/to-char/
   */
  private final def sparkDateFmtToSingleStoreFmtSpecifiers(format: UTF8String): String = {
    val translatedDateFmt =
      // Note: Order of string replacement for groups matters
      format
        .toString
        // SingleStore Four digit year
        .replaceAll("y{4}+", "YYYY")
        // SingleStore Two digit year
        .replaceAll("y{2}+", "YY")
        // SingleStore Month (January - December)
        .replaceAll("M{4}+", "MONTH")
        // SingleStore Three letter month (Jan - Dec)
        .replaceAll("M{3}+", "MON")
        // SingleStore Abbreviated day of week (Sun, Mon, Tue, etc)
        .replaceAll("E{1,3}", "DY")
        // SingleStore Day (1 - 31)
        .replaceAll("d{1,2}+", "DD")
        // SingleStore Hour (0 - 23)
        .replaceAll("H{1,2}+", "HH24")
        // SingleStore Hour (1 - 12)
        .replaceAll("h{1,2}+", "HH12")
        // SingleStore Minute (0 - 59)
        .replaceAll("m{1,2}+", "MI")
        // SingleStore Second (0 - 59)
        .replaceAll("s{1,2}+", "SS")
        // SingleStore AM or PM
        .replaceAll("a", "AM")

    log.info(
      logEventNameTagger(
        s"Translated Spark Date Format: `${format.toString}` " +
          s"to SingleStore Date Format Specifiers: `$translatedDateFmt`."
      )
    )

    translatedDateFmt
  }

  // helpers to keep this code sane
  def f(n: String, c: Joinable*): Statement              = func(n, c: _*)
  def op(o: String, l: Joinable, r: Joinable): Statement = block(l + o + r)
  def ifNeg(value: Joinable, valTrue: Joinable, valFalse: Joinable): Statement =
    f("IF", op("<", value, IntVar(0)), valTrue, valFalse)

  def makeDecimal(child: Joinable, precision: Int, scale: Int): Joinable = {
    val p = Math.min(SINGLESTORE_DECIMAL_MAX_PRECISION, precision)
    val s = Math.min(SINGLESTORE_DECIMAL_MAX_SCALE, scale)
    cast(child, s"DECIMAL($p, $s)")
  }

  def addMicroseconds(start: Joinable, v: CalendarInterval): Joinable =
    addMicroseconds(start, v.microseconds)

  def addMicroseconds(start: Joinable, v: Long): Joinable =
    if (v == 0) {
      start
    } else {
      f("DATE_ADD", start, Raw("INTERVAL") + v.toString + "MICROSECOND")
    }

  def addMonths(start: Joinable, v: CalendarInterval): Joinable =
    addMonths(start, v.months)

  def addMonths(start: Joinable, v: Int): Joinable =
    if (v == 0) {
      start
    } else {
      f("DATE_ADD", start, Raw("INTERVAL") + v.toString + "MONTH")
    }

  def subMicroseconds(start: Joinable, v: CalendarInterval): Joinable =
    if (v.microseconds == 0) {
      start
    } else {
      f("DATE_SUB", start, Raw("INTERVAL") + v.microseconds.toString + "MICROSECOND")
    }

  def subMonths(start: Joinable, v: CalendarInterval): Joinable =
    if (v.months == 0) {
      start
    } else {
      f("DATE_SUB", start, Raw("INTERVAL") + v.months.toString + "MONTH")
    }

  def longToDecimal(child: Joinable, p: Int, s: Int): Joinable =
    makeDecimal(op("/", child, math.pow(10.0, s).toString), p, s)

  def like(left: Joinable, right: Joinable): Joinable =
    op("LIKE", left, right)

  def likePatterns(child: Joinable, patterns: Seq[UTF8String], operation: String): Joinable = {
    patterns.foldLeft(op("LIKE", child, s"'${patterns.head.toString}'"))((patternA, patternB) => {
      op(operation, patternA, op("LIKE", child, s"'${patternB.toString}'"))
    })
  }

  // regexpFromStart adds a ^ prefix for singlestore regexp to match the beginning of the string (as Java does)
  def regexpFromStart(r: Joinable): Joinable = func("CONCAT", StringVar("^"), r)

  // computeNextDay returns a statement for computing the next date after startDate with specified offset (sunday -> 1, saturday -> 7)
  // ADDDATE(startDate,(dayOfWeekOffset - DAYOFWEEK(startDate) + 6)%7 +1)
  def computeNextDay(startDate: Joinable, offset: Joinable): Statement = f(
    "ADDDATE",
    startDate,
    op(
      "+",
      op(
        "%",
        op(
          "+",
          op(
            "-",
            offset,
            f("DAYOFWEEK", startDate)
          ),
          "6"
        ),
        "7"
      ),
      "1"
    )
  )

  object GenLiteral {
    def unapply(arg: Expression): Option[Joinable] = arg match {
      case Literal(v, _) if v == null      => Some(StringVar(null))
      case Literal(v: Int, DateType)       => Some(DateVar(DateTimeUtils.toJavaDate(v)))
      case Literal(v: Long, TimestampType) => Some(TimestampVar(DateTimeUtils.toJavaTimestamp(v)))

      case Literal(v, t) if !VersionSpecificUtil.isIntervalType(t) => {
        convertLiteralValue.lift(v)
      }

      case _ => None
    }

    def unapply(vals: Iterable[Any]): Option[Joinable] =
      vals
        .map(convertLiteralValue.lift)
        .reduce[Option[Joinable]] {
          case (Some(left), Some(right)) => Some(left + "," + right)
          case _                         => None
        }

    def convertLiteralValue: PartialFunction[Any, Joinable] = {
      case v if v == null => StringVar(null)

      case v: String     => StringVar(v)
      case v: UTF8String => StringVar(v.toString)
      case v: Byte       => ByteVar(v)

      case v: Boolean => Raw(if (v) "TRUE" else "FALSE")

      case v: Short                                  => Raw(v.toString)
      case v: Int                                    => Raw(v.toString)
      case v: Integer                                => Raw(v.toString)
      case v: Long                                   => Raw(v.toString)
      case v: Decimal                                => makeDecimal(Raw(v.toString), v.precision, v.scale)
      case v: BigDecimal                             => makeDecimal(Raw(v.toString), v.precision, v.scale)
      case v: Float if java.lang.Float.isFinite(v)   => Raw(v.toString)
      case v: Double if java.lang.Double.isFinite(v) => Raw(v.toString)
    }
  }

  case class FoldableExtractor[T]() {
    def unapply(e: Expression)(implicit tag: ClassTag[T]): Option[T] =
      if (e.foldable) {
        e.eval() match {
          case expr: T =>
            tag.unapply(expr)
          case _ => None
        }
      } else None
  }

  case class DecimalExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(e: Expression): Option[(Joinable, Int, Int)] = (e, e.dataType) match {
      case (expressionExtractor(child), t: DecimalType) => Some((child, t.precision, t.scale))
      case _                                            => None
    }
  }

  case class WindowBoundaryExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    val versionSpecificWindowBoundaryExpressionExtractor
      : VersionSpecificWindowBoundaryExpressionExtractor =
      VersionSpecificWindowBoundaryExpressionExtractor(expressionExtractor)
    def unapply(arg: Expression): Option[Joinable] = arg match {
      case versionSpecificWindowBoundaryExpressionExtractor(statement) => Some(statement)
      case e: SpecialFrameBoundary                                     => Some(e.sql)
      case Literal(n: Integer, IntegerType) =>
        Some(Raw(Math.abs(n).toString) + (if (n < 0) "PRECEDING" else "FOLLOWING"))
      case expressionExtractor(child) => Some(child + "FOLLOWING")
      case _                          => None
    }
  }

  case class AggregateExpressionExtractor(expressionExtractor: ExpressionExtractor,
                                          context: SQLGenContext) {
    def unapply(arg: AggregateExpression): Option[Joinable] = {
      val filterOption = arg.filter match {
        case None => Some(None)
        case Some(filter) =>
          expressionExtractor
            .unapply(filter)
            .map(f => Some(f))
      }
      filterOption.flatMap(filter => {
        val versionSpecificAggregateExpressionExtractor =
          VersionSpecificAggregateExpressionExtractor(expressionExtractor, context, filter)

        arg.aggregateFunction match {
          case Count(expression) =>
            (expression, arg.isDistinct, filter) match {
              case (expressionExtractor(None), false, filter) =>
                Some(aggregateWithFilter("COUNT", "1", filter))
              case (expressionExtractor(Some(children)), false, filter) =>
                Some(aggregateWithFilter("COUNT", children, filter))
              // DISTINCT and FILTER can't be used together
              case (expressionExtractor(Some(children)), true, None) =>
                Some(Raw("COUNT") + block(Raw("DISTINCT") + children))
              case _ => None
            }

          // Covariance.scala
          // TODO: case CovPopulation(expressionExtractor(left), expressionExtractor(right)) => ???
          // TODO: case CovSample(expressionExtractor(left), expressionExtractor(right))     => ???

          // Max.scala
          case Max(expressionExtractor(child)) =>
            Some(aggregateWithFilter("MAX", child, filter))

          // Min.scala
          case Min(expressionExtractor(child)) =>
            Some(aggregateWithFilter("MIN", child, filter))

          // BitAnd.scala
          case BitAndAgg(expressionExtractor(child))
              if context.singlestoreVersionAtLeast("7.0.1") =>
            Some(ExpressionGen.aggregateWithFilter("BIT_AND", child, filter))
          // BitOr.scala
          case BitOrAgg(expressionExtractor(child)) if context.singlestoreVersionAtLeast("7.0.1") =>
            Some(ExpressionGen.aggregateWithFilter("BIT_OR", child, filter))
          // BitXor.scala
          case BitXorAgg(expressionExtractor(child))
              if context.singlestoreVersionAtLeast("7.0.1") =>
            Some(ExpressionGen.aggregateWithFilter("BIT_XOR", child, filter))

          // HyperLogLogPlusPlus.scala
          case HyperLogLogPlusPlus(expressionExtractor(child), _, _, _) =>
            Some(aggregateWithFilter("APPROX_COUNT_DISTINCT", child, filter))

          case versionSpecificAggregateExpressionExtractor(statement) => Some(statement)

          //    case AggregateExpression(MaxBy(expressionExtractor(valueExpr), expressionExtractor(orderingExpr)), _, _, None, _) =>
          //    case AggregateExpression(MinBy(expressionExtractor(valueExpr), expressionExtractor(orderingExpr)), _, _, None, _) =>
          case _ => None
        }
      })
    }
  }

  // we need to manually unwrap MonthsBetween since the roundOff argument
  // does not exist in Spark 2.3
  // The roundOff argument truncates the result to 8 digits of precision
  // which we can safely ignore, the user can apply an explicit round if needed
  case class MonthsBetweenExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: MonthsBetween): Option[(Joinable, Joinable)] =
      for {
        date1 <- expressionExtractor.unapply(arg.date1)
        date2 <- expressionExtractor.unapply(arg.date2)
      } yield (date1, date2)
  }

  case class CaseWhenExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: CaseWhen): Option[Joinable] = {
      val condition =
        arg.branches.foldLeft(Option(stringToJoinable("")))((prefix: Option[Joinable], branch) => {
          prefix match {
            case Some(actualPrefix) =>
              branch match {
                case (expressionExtractor(whenCondition), expressionExtractor(thenCondition)) =>
                  Some(actualPrefix + Raw("WHEN") + whenCondition + Raw("THEN") + thenCondition)
                case _ => None
              }
            case None => None
          }
        })

      val elseCondition = arg.elseValue match {
        case Some(expressionExtractor(e)) => Some(Raw("ELSE") + e)
        case None                         => Some(Raw(""))
        case _                            => None
      }

      (condition, elseCondition) match {
        case (Some(c), Some(e)) => Some(block(Raw("CASE") + c + e + "END"))
        case _                  => None
      }
    }
  }

  case class AiqDayStartExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqDayStart): Option[Joinable] = {
      expressionExtractor.unapply(
        UnixMillis(
          ConvertTimezone(
            arg.timezone,
            Literal("UTC"),
            TruncTimestamp(
              Literal("DAY"),
              DateAdd(
                AiqFromUnixTime(arg.timestamp, SINGLESTORE_DEFAULT_TIME_FORMAT_LITERAL, arg.timezone),
                arg.plusDays
              ),
            )
          )
        )
      )
    }
  }

  case class AiqStringToDateExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqStringToDate): Option[Joinable] = {
      expressionExtractor.unapply(
        UnixMillis(
          ConvertTimezone(
            arg.timeZone,
            Literal("UTC"),
            ParseToTimestamp(arg.dateStr, Some(arg.format), TimestampType),
          )
        )
      )
    }
  }

  case class AiqDateToStringExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqDateToString): Option[Joinable] = {
      expressionExtractor.unapply(AiqFromUnixTime(arg.timestamp, arg.dateFormat, arg.timezoneId))
    }
  }

  // This is an AIQ map to the offset of the epoch date to the imposed "first day of the week."
  // (Default Sunday). The Epoch date is a thursday (offset = 0).
  // scalastyle:off line.size.limit
  // https://github.com/ActionIQ/aiq/blob/master/libs/flame_utils/src/main/scala/co/actioniq/flame/SqlFunctions.scala#L356-L366
  // scalastyle:on line.size.limit
  private def getAiqDayOfWeekFromString(dayStr: String): Int = {
    val dowString = dayStr.toUpperCase()
    dowString match {
      case "SU" | "SUN" | "SUNDAY" => 4
      case "MO" | "MON" | "MONDAY" => 3
      case "TU" | "TUE" | "TUESDAY" => 2
      case "WE" | "WED" | "WEDNESDAY" => 1
      case "TH" | "THU" | "THURSDAY" => 0
      case "FR" | "FRI" | "FRIDAY" => 6
      case "SA" | "SAT" | "SATURDAY" => 5
      case _ =>
        throw new IllegalArgumentException(s"""Illegal input for day of week: $dayStr""")
    }
  }

  case class AiqWeekDiffExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqWeekDiff): Option[Joinable] = {
      if (arg.startDay.foldable) {
        val startDayInt = getAiqDayOfWeekFromString(arg.startDay.eval().toString)
        val Seq(daysSinceEndExpr, daysSinceStartExpr) =
          Seq(arg.endTs, arg.startTs).map { expr =>
            // Wrapping in `FLOOR` because Casting to Int creates data issues. Example:
            // - Java/Spark: (18140 + 3) / 7 = 2591 (Original value: 2591.8571428571427)
            // - SingleStore: SELECT ((18140 + 3) / 7) !:>int = 2592 (Original value: 2591.8571)
            Floor(
              Divide(
                // Using FROM_UNIXTIME(0) - 1 as `start_unix_time_ms` here since: SingleStore starts from
                //  - SingleStore: DATE(FROM_UNIXTIME('0')) = 1970-01-01
                //  - Spark: spark.sql("select date(from_unixtime(0))").collect() = Array([1969-12-31])
                Add(AiqDayDiff(DateFromUnixDate(Literal(-1)), expr, arg.timezoneId), Literal(startDayInt)),
                Literal(7),
              )
            )
          }

        expressionExtractor.unapply(Subtract(daysSinceEndExpr, daysSinceStartExpr))
      } else { None }
    }
  }

  case class AiqDayDiffExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqDayDiff): Option[Joinable] = {
      expressionExtractor.unapply(
        DateDiff(
          AiqFromUnixTime(arg.endTs, SINGLESTORE_DEFAULT_TIME_FORMAT_LITERAL, arg.timezoneId),  // endDate
          AiqFromUnixTime(arg.startTs, SINGLESTORE_DEFAULT_TIME_FORMAT_LITERAL, arg.timezoneId) // startDate
        )
      )
    }
  }

  case class AiqDayOfTheWeekExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqDayOfTheWeek): Option[Joinable] = {
      expressionExtractor.unapply(
        Decode(
          Seq(
            WeekDay(
              AiqFromUnixTime(
                arg.epochTimestamp,
                SINGLESTORE_DEFAULT_TIME_FORMAT_LITERAL,
                arg.timezoneId
              )
            ),
            Literal(0), Literal("monday"),
            Literal(1), Literal("tuesday"),
            Literal(2), Literal("wednesday"),
            Literal(3), Literal("thursday"),
            Literal(4), Literal("friday"),
            Literal(5), Literal("saturday"),
            Literal(6), Literal("sunday"),
          ),
          Literal.default(NullType),
        )
      )
    }
  }

  case class AiqFromUnixTimeExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqFromUnixTime): Option[Joinable] = {
      if (arg.format.foldable) {
        expressionExtractor.unapply(
          DateFormatClass(
            ConvertTimezone(
              CurrentTimeZone(),
              arg.timeZone,
              FromUnixTime(
                If(
                  // 13 Chars => Epoch Millisecond Precision
                  EqualTo(Length(Cast(arg.sec, StringType)), Literal(13)),
                  Floor(Divide(arg.sec, Literal(1000))),
                  arg.sec
                ),
                SINGLESTORE_DEFAULT_TIME_FORMAT_LITERAL
              )
            ),
            arg.format
          )
        )
      } else { None }
    }
  }

  case class AiqStringCompareCiExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqStringCompareCi): Option[Joinable] = {
      expressionExtractor.unapply(EqualTo(Lower(arg.left), Lower(arg.right)))
    }
  }

  case class AiqStringCompareNeqCiExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: AiqStringCompareNeqCi): Option[Joinable] = {
      expressionExtractor.unapply(Not(AiqStringCompareCi(arg.left, arg.right)))
    }
  }

  case class PromotePrecisionExpressionExtractor(expressionExtractor: ExpressionExtractor) {
    def unapply(arg: PromotePrecision): Option[Joinable] = {
      expressionExtractor.unapply(arg.child)
    }
  }

  def aggregateWithFilter(
    funcName: String,
    child: Joinable,
    filter: Option[Joinable],
    extraArgs: Seq[Joinable] = Seq()
  ): Statement = {
    filter match {
      case Some(filterExpression) =>
        f(funcName, Seq(f("IF", filterExpression, child, StringVar(null))) ++ extraArgs: _*)
      case None => f(funcName, Seq(child) ++ extraArgs: _*)
    }
  }

  val intFoldableExtractor: FoldableExtractor[Int]               = FoldableExtractor[Int]()
  val doubleFoldableExtractor: FoldableExtractor[Double]         = FoldableExtractor[Double]()
  val numberFoldableExtractor: FoldableExtractor[Number]         = FoldableExtractor[Number]()
  val utf8StringFoldableExtractor: FoldableExtractor[UTF8String] = FoldableExtractor[UTF8String]()

  def apply(expressionExtractor: ExpressionExtractor): PartialFunction[Expression, Joinable] = {
    val caseWhenExpressionExtractor        = CaseWhenExpressionExtractor(expressionExtractor)
    val windowBoundaryExpressionExtractor  = WindowBoundaryExpressionExtractor(expressionExtractor)
    val monthsBetweenExpressionExtractor   = MonthsBetweenExpressionExtractor(expressionExtractor)
    val context                            = expressionExtractor.context
    val aggregateExpressionExtractor       = AggregateExpressionExtractor(expressionExtractor, context)
    val decimalExpressionExtractor         = DecimalExpressionExtractor(expressionExtractor)
    val versionSpecificExpressionGen       = VersionSpecificExpressionGen(expressionExtractor)

    val aiqDayStartExpressionExtractor     = AiqDayStartExpressionExtractor(expressionExtractor)
    val aiqStringToDateExpressionExtractor = AiqStringToDateExpressionExtractor(expressionExtractor)
    val aiqDateToStringExpressionExtractor = AiqDateToStringExpressionExtractor(expressionExtractor)
    val aiqDayDiffExpressionExtractor      = AiqDayDiffExpressionExtractor(expressionExtractor)
    val aiqWeekDiffExpressionExtractor     = AiqWeekDiffExpressionExtractor(expressionExtractor)
    val aiqDayOfTheWeekExpressionExtractor = AiqDayOfTheWeekExpressionExtractor(expressionExtractor)
    val aiqFromUnixTimeExpressionExtractor = AiqFromUnixTimeExpressionExtractor(expressionExtractor)

    val aiqStringCompareCiExpressionExtractor    = AiqStringCompareCiExpressionExtractor(expressionExtractor)
    val aiqStringCompareNeqCiExpressionExtractor = AiqStringCompareNeqCiExpressionExtractor(expressionExtractor)

    val promotePrecisionExpressionExtractor = PromotePrecisionExpressionExtractor(expressionExtractor)

    return {
      // ----------------------------------
      // Attributes
      // ----------------------------------
      case a: Attribute => Attr(a, context)
      case a @ Alias(expressionExtractor(child), name) =>
        alias(child, name, a.exprId, context)

      // ----------------------------------
      // Literals
      // ----------------------------------
      case GenLiteral(v) => v

      // ----------------------------------
      // Variable Expressions
      // ----------------------------------

      case Coalesce(expressionExtractor(Some(child))) => f("COALESCE", child)
      case Least(expressionExtractor(Some(child)))    => f("LEAST", child)
      case Greatest(expressionExtractor(Some(child))) => f("GREATEST", child)
      case Concat(expressionExtractor(Some(child)))   => f("CONCAT", child)

      // ----------------------------------
      // Aggregate Expressions
      // ----------------------------------
      case aggregateExpressionExtractor(expression) => expression

      // windowExpressions.scala
      case WindowExpression(expressionExtractor(child),
                            WindowSpecDefinition(expressionExtractor(partitionSpec),
                                                 expressionExtractor(orderSpec),
                                                 expressionExtractor(frameSpec))) =>
        child + "OVER" + block(
          partitionSpec.map(Raw("PARTITION BY") + _).getOrElse(empty) +
            orderSpec.map(Raw("ORDER BY") + _).getOrElse(empty) +
            frameSpec
        )

      case UnspecifiedFrame => ""

      case SpecifiedWindowFrame(frameType,
                                windowBoundaryExpressionExtractor(lower),
                                windowBoundaryExpressionExtractor(upper)) =>
        Raw(frameType.sql) + "BETWEEN" + lower + "AND" + upper

      case RowNumber()                       => "ROW_NUMBER()"
      case NTile(expressionExtractor(child)) => f("NTILE", child)
      case Rank(_)                           => "RANK()"
      case DenseRank(_)                      => "DENSE_RANK()"
      case PercentRank(_)                    => "PERCENT_RANK()"

      // TODO: case CumeDist()               => ???

      // ----------------------------------
      // Binary Expressions
      // ----------------------------------

      // bitwiseExpressions.scala
      case BitwiseAnd(expressionExtractor(left), expressionExtractor(right)) => op("&", left, right)
      case BitwiseOr(expressionExtractor(left), expressionExtractor(right))  => op("|", left, right)
      case BitwiseXor(expressionExtractor(left), expressionExtractor(right)) => op("^", left, right)

      // datetimeExpressions.scala

      // NOTE: we explicitly ignore the timeZoneId field in all of the following expressions
      // The user is required to setup Spark and/or SingleStore with the timezone they want or they
      // will get inconsistent results with/without pushdown.

      case DateAdd(expressionExtractor(startDate), expressionExtractor(days)) =>
        f("ADDDATE", startDate, days)
      case DateSub(expressionExtractor(startDate), expressionExtractor(days)) =>
        f("SUBDATE", startDate, days)

      case TimeAdd(expressionExtractor(start),
                   Literal(v: CalendarInterval, CalendarIntervalType),
                   timeZoneId) => {
        def addDays(start: Joinable) =
          if (v.days == 0) {
            start
          } else {
            f("DATE_ADD", start, Raw("INTERVAL") + v.days.toString + "DAY")
          }

        addMicroseconds(addDays(addMonths(start, v)), v)
      }

      case FromUTCTimestamp(expressionExtractor(timestamp), expressionExtractor(timezone)) =>
        f("CONVERT_TZ", timestamp, StringVar("UTC"), timezone)

      case ToUTCTimestamp(expressionExtractor(timestamp), expressionExtractor(timezone)) =>
        f("CONVERT_TZ", timestamp, timezone, StringVar("UTC"))

      case TruncTimestamp(expressionExtractor(format),
                          expressionExtractor(timestamp),
                          timeZoneId) => {
        f(
          "DATE_TRUNC",
          sqlMapValueCaseInsensitive(
            format,
            Map(
              // SingleStore doesn't support formats ("yyyy", "yy", "mon", "mm", "dd") so we map them here
              "yyyy" -> "year",
              "yy"   -> "year",
              "mon"  -> "month",
              "mm"   -> "month",
              "dd"   -> "day"
            ),
            format
          ),
          timestamp
        )
      }

      case TruncDate(expressionExtractor(date), expressionExtractor(format)) => {
        f(
          "DATE_TRUNC",
          sqlMapValueCaseInsensitive(
            format,
            Map(
              // SingleStore doesn't support formats ("yyyy", "yy", "mon", "mm") so we map them here
              "yyyy" -> "year",
              "yy"   -> "year",
              "mon"  -> "month",
              "mm"   -> "month"
            ),
            format
          ),
          date
        )
      }

      case monthsBetweenExpressionExtractor((date1, date2)) =>
        f("MONTHS_BETWEEN", date1, date2)

      case caseWhenExpressionExtractor(caseWhenStatement) => caseWhenStatement

      case AddMonths(expressionExtractor(startDate), expressionExtractor(numMonths)) =>
        f("DATE_ADD", startDate, Raw("INTERVAL") + numMonths + "MONTH")

      // SingleStore and spark support other date formats
      // UnixTime doesn't use format if time is already a dataType or TimestampType

      case FromUnixTime(expressionExtractor(sec), utf8StringFoldableExtractor(format), _) =>
        val funcArgs = if (format == SINGLESTORE_DEFAULT_TIME_FORMAT) {
          Seq(sec)
        } else {
          Seq(sec, StringVar(sparkDateFmtToSingleStoreFmtSymbols(format)))
        }

        f("FROM_UNIXTIME", funcArgs: _*)

      case DateDiff(expressionExtractor(endDate), expressionExtractor(startDate)) =>
        f("DATEDIFF", endDate, startDate)

      // Used by `ParseToTimestamp` & `ParseToDate` when format is != None
      case GetTimestamp(expressionExtractor(left),
                        utf8StringFoldableExtractor(format),
                        _,
                        _,
                        _) =>
        f("TO_TIMESTAMP", left, StringVar(sparkDateFmtToSingleStoreFmtSpecifiers(format)))
      case ParseToTimestamp(e @ expressionExtractor(left), formatOpt, _, _)
          if e.resolved && e.dataType.isInstanceOf[StringType] =>
        formatOpt match {
          case Some(utf8StringFoldableExtractor(format)) =>
            f("TO_TIMESTAMP", left, StringVar(sparkDateFmtToSingleStoreFmtSpecifiers(format)))
          case None => f("TIMESTAMP", left)
        }
      case ParseToDate(e @ expressionExtractor(left), None, _)
          if e.resolved && e.dataType.isInstanceOf[StringType] =>
        f("DATE", left)
      case DateFormatClass(expressionExtractor(left),
                           utf8StringFoldableExtractor(right),
                           _) =>
        f("DATE_FORMAT", left, StringVar(sparkDateFmtToSingleStoreFmtSymbols(right)))

      case aiqDayStartExpressionExtractor(aiqDayStartStatement)         => aiqDayStartStatement
      case aiqStringToDateExpressionExtractor(aiqStringToDateStatement) => aiqStringToDateStatement
      case aiqDateToStringExpressionExtractor(aiqDateToStringStatement) => aiqDateToStringStatement
      case aiqDayDiffExpressionExtractor(aiqDayDiffStatement)           => aiqDayDiffStatement
      case aiqWeekDiffExpressionExtractor(aiqWeekDiffStatement)         => aiqWeekDiffStatement
      case aiqDayOfTheWeekExpressionExtractor(aiqDayOfTheWeekStatement) => aiqDayOfTheWeekStatement
      case aiqFromUnixTimeExpressionExtractor(aiqFromUnixTimeStatement) => aiqFromUnixTimeStatement

      // mathExpressions.scala
      case Atan2(expressionExtractor(left), expressionExtractor(right))     => f("ATAN2", left, right)
      case Pow(expressionExtractor(left), expressionExtractor(right))       => f("POWER", left, right)
      case ShiftLeft(expressionExtractor(left), expressionExtractor(right)) => op("<<", left, right)
      case ShiftRight(expressionExtractor(left), expressionExtractor(right)) =>
        op(">>", left, right)
      // TODO: case ShiftRightUnsigned(expressionExtractor(left), expressionExtractor(right)) => op(">>", left, right)
      case Logarithm(expressionExtractor(left), expressionExtractor(right)) => f("LOG", left, right)
      case Hypot(expressionExtractor(left), expressionExtractor(right)) =>
        f("SQRT", op("+", f("POW", left, "2"), f("POW", right, "2")))

      // TODO: case _: BRound => None

      // predicates.scala
      case And(expressionExtractor(left), expressionExtractor(right)) => op("AND", left, right)
      case Or(expressionExtractor(left), expressionExtractor(right))  => op("OR", left, right)

      case EqualTo(expressionExtractor(left), expressionExtractor(right)) => op("=", left, right)
      case EqualNullSafe(expressionExtractor(left), expressionExtractor(right)) =>
        op("<=>", left, right)
      case LessThan(expressionExtractor(left), expressionExtractor(right)) => op("<", left, right)
      case LessThanOrEqual(expressionExtractor(left), expressionExtractor(right)) =>
        op("<=", left, right)
      case GreaterThan(expressionExtractor(left), expressionExtractor(right)) =>
        op(">", left, right)
      case GreaterThanOrEqual(expressionExtractor(left), expressionExtractor(right)) =>
        op(">=", left, right)

      case If(expressionExtractor(predicate),
              expressionExtractor(trueValue),
              expressionExtractor(falseValue)) =>
        f("IF", predicate, trueValue, falseValue)

      case In(expressionExtractor(child), expressionExtractor(Some(elements))) =>
        op("IN", child, block(elements))

      case InSet(expressionExtractor(child), GenLiteral(elements)) =>
        op(
          "IN",
          child,
          block(elements)
        )

      // regexpExpressions.scala
      case Like(expressionExtractor(left), expressionExtractor(right), escapeChar: Char) =>
        if (escapeChar == '\\') {
          op("LIKE", left, right)
        } else {
          op("LIKE", left, f("REPLACE", right, "'" + escapeChar.toString + "'", "'\\\\'"))
        }
      case RLike(expressionExtractor(left), expressionExtractor(right)) =>
        op("RLIKE", left, regexpFromStart(right))

      // stringExpressions.scala
      case Contains(expressionExtractor(left), expressionExtractor(right)) =>
        op(">", f("INSTR", left, right), "0")
      case StartsWith(expressionExtractor(left), expressionExtractor(right)) =>
        op("LIKE", left, f("CONCAT", right, StringVar("%")))
      case EndsWith(expressionExtractor(left), expressionExtractor(right)) =>
        op("LIKE", left, f("CONCAT", StringVar("%"), right))
      case StringInstr(expressionExtractor(str), expressionExtractor(substr)) =>
        f("INSTR", str, substr)
      case FormatNumber(expressionExtractor(x), e @ expressionExtractor(d))
          if e.dataType == IntegerType =>
        ifNeg(d, StringVar(null), f("FORMAT", x, d))
      case StringRepeat(expressionExtractor(child), expressionExtractor(times)) =>
        f("LPAD",
          StringVar(""),
          ifNeg(times, IntVar(0), times) + "*" + f("CHAR_LENGTH", child),
          child)

      case StringTrim(expressionExtractor(srcStr), trimStrOptExp) =>
        trimStrOptExp match {
          case Some(utf8StringFoldableExtractor(trimStr)) =>
            f("TRIM", Raw("BOTH") + s"'${trimStr.toString}'" + "FROM" + srcStr)
          case None => f("TRIM", Raw("BOTH") + "FROM" + srcStr)
        }

      case StringTrimLeft(expressionExtractor(srcStr), trimStrOptExp) =>
        trimStrOptExp match {
          case Some(utf8StringFoldableExtractor(trimStr)) =>
            // https://docs.singlestore.com/cloud/reference/sql-reference/string-functions/trim/
            f("TRIM", Raw("LEADING") + s"'${trimStr.toString}'" + "FROM" + srcStr)
          case None => f("LTRIM", srcStr)
        }

      case StringTrimRight(expressionExtractor(srcStr), trimStrOptExp) =>
        trimStrOptExp match {
          case Some(utf8StringFoldableExtractor(trimStr)) =>
            // https://docs.singlestore.com/cloud/reference/sql-reference/string-functions/trim/
            f("TRIM", Raw("TRAILING") + s"'${trimStr.toString}'" + "FROM" + srcStr)
          case None => f("RTRIM", srcStr)
        }

      case FindInSet(expressionExtractor(left), utf8StringFoldableExtractor(right)) => {
        val str_array    = right.toString.split(',')
        var caseBranches = stringToJoinable("")
        for (i <- 1 to str_array.length) {
          caseBranches += Raw(s"WHEN '${str_array(i - 1)}'")
          caseBranches += Raw(s"THEN '${i.toString}'")
        }
        block(Raw("CASE") + left + caseBranches + Raw("ELSE 0 END"))
      }

      // TODO: case _: Levenshtein => None

      // ----------------------------------
      // Leaf Expressions
      // ----------------------------------

      // datetimeExpressions.scala
      case CurrentDate(_)     => "CURRENT_DATE()"
      case CurrentTimestamp() => "NOW(6)"
      case CurrentTimeZone()  => "@@SYSTEM_TIME_ZONE"

      // mathExpressions.scala
      case EulerNumber() => math.E.toString
      case Pi()          => "PI()"

      // TODO: case RegExpExtract(expressionExtractor(subject), expressionExtractor(regexp), expressionExtractor(idx)) => ???

      // stringExpressions.scala
      case StringReplace(expressionExtractor(srcExpr),
                         expressionExtractor(searchExpr),
                         expressionExtractor(replaceExpr)) =>
        f("REPLACE", srcExpr, searchExpr, replaceExpr)
      case SubstringIndex(expressionExtractor(strExpr),
                          expressionExtractor(delimExpr),
                          expressionExtractor(countExpr)) =>
        f("SUBSTRING_INDEX", strExpr, delimExpr, countExpr)
      case StringLocate(expressionExtractor(substr),
                        expressionExtractor(str),
                        expressionExtractor(start)) =>
        f("LOCATE", substr, str, start)
      case StringLPad(expressionExtractor(str),
                      expressionExtractor(len),
                      expressionExtractor(pad)) =>
        f("LPAD", str, ifNeg(len, IntVar(0), len), pad)
      case StringRPad(expressionExtractor(str),
                      expressionExtractor(len),
                      expressionExtractor(pad)) =>
        f("RPAD", str, ifNeg(len, IntVar(0), len), pad)
      case Substring(expressionExtractor(str),
                     expressionExtractor(pos),
                     expressionExtractor(len)) =>
        f("SUBSTR", str, pos, len)

      case Overlay(expressionExtractor(input),
                   expressionExtractor(replace),
                   expressionExtractor(pos),
                   expressionExtractor(len)) =>
        f(
          "IF",
          op("<", len, IntVar(0)),
          f("CONCAT",
            f("LEFT", input, op("-", pos, "1")),
            replace,
            f("SUBSTR", input, op("+", f("LENGTH", replace), pos))),
          f("CONCAT",
            f("LEFT", input, op("-", pos, "1")),
            replace,
            f("SUBSTR", input, op("+", pos, len)))
        )

      case StringTranslate(expressionExtractor(srcExpr),
                           utf8StringFoldableExtractor(matchingExpr),
                           utf8StringFoldableExtractor(replaceExpr)) => {
        var replaceContent  = srcExpr
        val replaceExprLen  = replaceExpr.toString.length
        val matchingExprLen = matchingExpr.toString.length
        for (i <- 0 to Math.max(replaceExprLen, matchingExprLen) - 1) {
          val matchingCurrCharacter = if (i < matchingExprLen) {
            s"'${matchingExpr.toString.charAt(i)}'"
          } else {
            "''"
          }
          val replaceCurrCharacter = if (i < replaceExprLen) {
            s"'${replaceExpr.toString.charAt(i)}'"
          } else {
            "''"
          }
          replaceContent = f("REPLACE", replaceContent, matchingCurrCharacter, replaceCurrCharacter)
        }
        replaceContent
      }

      case Decode(expressionExtractor(Some(params)), expressionExtractor(replacement)) =>
        f("DECODE", params, replacement)

      case aiqStringCompareCiExpressionExtractor(aiqStringCompareCiStatement)       => aiqStringCompareCiStatement
      case aiqStringCompareNeqCiExpressionExtractor(aiqStringCompareNeqCiStatement) => aiqStringCompareNeqCiStatement

      // ----------------------------------
      // Unary Expressions
      // ----------------------------------

      // arithmetic.scala
      case UnaryPositive(expressionExtractor(child)) => f("+", child)

      // bitwiseExpression.scala
      case BitwiseNot(expressionExtractor(expr)) => f("~", expr)

      case BitwiseCount(expressionExtractor(child)) =>
        f("BIT_COUNT", child)

      // TODO: case UpCast(expressionExtractor(child), dataType, walkedTypePath) => ???

      // datetimeExpressions.scala
      case Hour(expressionExtractor(child), _)     => f("HOUR", child)
      case Minute(expressionExtractor(child), _)   => f("MINUTE", child)
      case Second(expressionExtractor(child), _)   => f("SECOND", child)
      case DayOfYear(expressionExtractor(child))   => f("DAYOFYEAR", child)
      case Year(expressionExtractor(child))        => f("YEAR", child)
      case Quarter(expressionExtractor(child))     => f("QUARTER", child)
      case Month(expressionExtractor(child))       => f("MONTH", child)
      case DayOfMonth(expressionExtractor(child))  => f("DAY", child)
      case DayOfWeek(expressionExtractor(child))   => f("DAYOFWEEK", child)
      case WeekDay(expressionExtractor(child))     => f("WEEKDAY", child)
      case WeekOfYear(expressionExtractor(child))  => f("WEEK", child, "3")
      case LastDay(expressionExtractor(startDate)) => f("LAST_DAY", startDate)
      case Now()                                   => f("NOW")

      //    case DatePart(expressionExtractor(field), expressionExtractor(source), expressionExtractor(child)) => // Converts to CAST(field)

      case Extract(utf8StringFoldableExtractor(field), expressionExtractor(source), _) =>
        f("EXTRACT", field.toString + "FROM" + source)

      //    case MakeInterval(_, _, _, _, _, _, _) => ???

      // MakeDecimal and UnscaledValue value are used in DecimalAggregates optimizer
      // This optimizer replace Decimals inside of the sum and aggregate expressions to the Longs using UnscaledValue
      // and then casts the result back to Decimal using MakeDecimal
      case MakeDecimal(expressionExtractor(child), p, s, _) =>
        longToDecimal(child, p, s)

      case UnscaledValue(decimalExpressionExtractor(child, precision, scale)) =>
        op("!:>", op("*", child, math.pow(10.0, scale).toString), "BIGINT")

      // hash.scala
      case Md5(expressionExtractor(child))  => f("MD5", child)
      case Sha1(expressionExtractor(child)) => f("SHA1", child)
      case Sha2(expressionExtractor(left), right)
          if right.foldable &&
            right.eval().isInstanceOf[Int] &&
            right.eval().asInstanceOf[Int] != 224 =>
        f("SHA2", left, right.toString)
      case Crc32(expressionExtractor(child)) => f("CRC32", child)

      //jsonExpressions.scala
      case GetJsonObject(expressionExtractor(json), utf8StringFoldableExtractor(path))
          if path.toString.length >= 2 & path.toString.startsWith("$.") => {
        val pathParts = path.toString.substring(2).split("\\.")
        val goalPath  = pathParts.last
        var jsonQuery = json
        for (i <- 0 to (pathParts.length - 2)) {
          jsonQuery = f("JSON_EXTRACT_JSON", jsonQuery, StringVar(pathParts(i)))
        }
        f(
          "IF",
          op("=",
             f("JSON_GET_TYPE", f("JSON_EXTRACT_JSON", jsonQuery, StringVar(goalPath))),
             StringVar("string")),
          f("JSON_EXTRACT_STRING", jsonQuery, StringVar(goalPath)),
          f("JSON_EXTRACT_JSON", jsonQuery, StringVar(goalPath))
        )
      }

      // mathExpressions.scala
      case Acos(expressionExtractor(child))      => f("ACOS", child)
      case Asin(expressionExtractor(child))      => f("ASIN", child)
      case Atan(expressionExtractor(child))      => f("ATAN", child)
      case Ceil(expressionExtractor(child))      => f("CEIL", child)
      case Cos(expressionExtractor(child))       => f("COS", child)
      case Exp(expressionExtractor(child))       => f("EXP", child)
      case Expm1(expressionExtractor(child))     => block(func("EXP", child) + "- 1")
      case Floor(expressionExtractor(child))     => f("FLOOR", child)
      case Log(expressionExtractor(child))       => f("LOG", child)
      case Log2(expressionExtractor(child))      => f("LOG2", child)
      case Log10(expressionExtractor(child))     => f("LOG10", child)
      case Log1p(expressionExtractor(child))     => f("LOG", child + "+ 1")
      case Signum(expressionExtractor(child))    => f("SIGN", child)
      case Sin(expressionExtractor(child))       => f("SIN", child)
      case Sqrt(expressionExtractor(child))      => f("SQRT", child)
      case Tan(expressionExtractor(child))       => f("TAN", child)
      case Cot(expressionExtractor(child))       => f("COT", child)
      case ToDegrees(expressionExtractor(child)) => f("DEGREES", child)
      case ToRadians(expressionExtractor(child)) => f("RADIANS", child)
      case Bin(expressionExtractor(child))       => f("BIN", child)
      case Hex(expressionExtractor(child))       => f("HEX", child)

      //    case BoolAnd(expressionExtractor(arg)) => // Spark can't apply bool_and to smallint (Input to function 'bool_and' should have been boolean, but it's [smallint])
      //    case BoolOr(expressionExtractor(arg))  => // Spark can't apply bool_or to smallint (Input to function 'bool_or' should have been boolean, but it's [smallint])
      //    case ArrayForAll(expressionExtractor(arg), expressionExtractor(function))                    => ???
      //    case SchemaOfCsv(expressionExtractor(child), options)                               => ???
      //    case MapEntries(expressionExtractor(child))                                         => ???
      //    case MapFilter(expressionExtractor(arg), expressionExtractor(function))                      => ???
      //    case MapZipWith(expressionExtractor(left), expressionExtractor(right), expressionExtractor(function)) => ???
      //    case CsvToStructs(schema, options, expressionExtractor(child), timeZoneId)          => ???
      //    case StructsToCsv(options, expressionExtractor(child), timeZoneId)                  => ???
      //    case SparkVersion()                                                        => ???
      //    case TransformKeys(expressionExtractor(argument), expressionExtractor(function))             => ???
      //    case TransformValues(expressionExtractor(argument), expressionExtractor(function))           => ???
      //    case XxHash64(children, seed) => // we have 32-bit hash, but don't have 64-bit

      // tanh(x) = (exp(x) - exp(-x)) / (exp(x) + exp(-x))
      case Tanh(expressionExtractor(child)) =>
        op("/",
           op("-", f("EXP", child), f("EXP", f("-", child))),
           op("+", f("EXP", child), f("EXP", f("-", child))))

      // sinh(x) = (exp(x) - exp(-x)) / 2
      case Sinh(expressionExtractor(child)) =>
        op("/", op("-", f("EXP", child), f("EXP", f("-", child))), "2")

      // cosh(x) = (exp(x) + exp(-x)) / 2
      case Cosh(expressionExtractor(child)) =>
        op("/", op("+", f("EXP", child), f("EXP", f("-", child))), "2")

      // asinh(x) = ln(x + sqrt(x^2 + 1))
      case Asinh(expressionExtractor(child)) =>
        f("LN", op("+", child, f("SQRT", op("+", f("POW", child, "2"), "1"))))

      // acosh(x) = ln(x + sqrt(x^2 - 1))
      case Acosh(expressionExtractor(child)) =>
        f("LN", op("+", child, f("SQRT", op("-", f("POW", child, "2"), "1"))))

      // atanh(x) = 1/2 * ln((1 + x)/(1 - x))
      case Atanh(expressionExtractor(child)) =>
        op("/", f("LN", op("/", op("+", "1", child), op("-", "1", child))), "2")

      case Rint(expressionExtractor(child)) => f("ROUND", child, "0")

      // TODO: case Factorial(expressionExtractor(child)) => ???
      // TODO: case Cbrt(expressionExtractor(child))      => f("POW", child, op("/", "1", "3"))
      //  We need to wait for the engine to implement precise cbrt

      // nullExpressions.scala
      case NullIf(expressionExtractor(left), expressionExtractor(right), _) =>
        f("NULLIF", left, right)
      case Nvl(expressionExtractor(left), expressionExtractor(right), _) =>
        f("COALESCE", left, right)
      case IsNull(expressionExtractor(child))    => block(child) + "IS NULL"
      case IsNotNull(expressionExtractor(child)) => block(child) + "IS NOT NULL"

      case Nvl2(expressionExtractor(expr1),
                expressionExtractor(expr2),
                expressionExtractor(expr3),
                _) =>
        f("IF", expr1 + "IS NOT NULL", expr2, expr3)

      // predicates.scala
      case Not(expressionExtractor(child)) => block(Raw("NOT") + child)
      case If(expressionExtractor(predicate),
              expressionExtractor(trueValue),
              expressionExtractor(falseValue)) =>
        f("IF", predicate, trueValue, falseValue)

      // TODO: case Randn(expressionExtractor(child)) => ???

      // SortOrder.scala
      // in SingleStore, nulls always come first when direction = ascending
      case SortOrder(expressionExtractor(child), Ascending, NullsFirst, _) => block(child) + "ASC"
      // in SingleStore, nulls always come last when direction = descending
      case SortOrder(expressionExtractor(child), Descending, NullsLast, _) => block(child) + "DESC"

      // stringExpressions.scala
      case Upper(expressionExtractor(child))          => f("UPPER", child)
      case Lower(expressionExtractor(child))          => f("LOWER", child)
      case ConcatWs(expressionExtractor(Some(child))) => f("CONCAT_WS", child)

      case StringSpace(expressionExtractor(child)) =>
        f("LPAD", StringVar(""), child, StringVar(" "))

      case Length(expressionExtractor(child))      => f("CHAR_LENGTH", child)
      case BitLength(expressionExtractor(child))   => block(func("LENGTH", child) + "* 8")
      case OctetLength(expressionExtractor(child)) => f("LENGTH", child)
      case Ascii(expressionExtractor(child))       => f("ASCII", child)
      case Chr(expressionExtractor(child)) =>
        f("IF", f("ISNULL", child), StringVar(null), f("CHAR", child))

      case versionSpecificExpressionGen(child) => child

      case Uuid(_) if context.singlestoreVersionAtLeast("7.5.0") => "UUID()"

      case InitCap(expressionExtractor(child)) => f("INITCAP", child)

      case Reverse(e @ expressionExtractor(child))
        if e.resolved && e.dataType.isInstanceOf[StringType] =>
        f("REVERSE", child)
      // TODO: case SoundEx(expressionExtractor(child)) => ???

      // Used by DecimalPrecision to promote the precision of DecimalType and avoid promote multiple times
      case promotePrecisionExpressionExtractor(promotePrecisionStatement) => promotePrecisionStatement
    }
  }
}
