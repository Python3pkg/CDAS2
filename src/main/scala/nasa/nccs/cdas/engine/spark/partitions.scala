package nasa.nccs.cdas.engine.spark
import nasa.nccs.cdapi.data.RDDPartition
import nasa.nccs.utilities.Loggable
import org.apache.spark.Partitioner
import org.apache.spark.rdd.RDD
import ucar.nc2.time.CalendarDate

object LongRange {
  type StrRep = (Long) => String
  implicit def ordering[A <: LongRange]: Ordering[A] = Ordering.by(_.start)
  def apply( start: Long, end: Long ): LongRange = new LongRange( start, end )

  def apply( ranges: Iterable[LongRange] ): LongRange = {
    val startMS = ranges.foldLeft( Long.MaxValue )( ( tval, key ) => Math.min( tval, key.start ) )
    val endMS = ranges.foldLeft( Long.MinValue )( ( tval, key ) => Math.max( tval, key.end) )
    new LongRange( startMS, endMS )
  }
  implicit val strRep: LongRange.StrRep = _.toString
}

class LongRange(val start: Long, val end: Long ) extends Serializable {
  import LongRange._
  def this( r: LongRange ) { this( r.start, r.end ) }
  def center = (start + end)/2
  val size: Double = (end - start).toDouble
  override def equals(other: Any): Boolean = other match {
    case tp: LongRange => ( tp.start == start) && ( tp.end == end)
    case _ => false
  }
  def compare( that: LongRange ): Int = start.compare( that.start )
  def getRelPos( location: Long ): Double =
    (location - start) / size
  def intersect( other: LongRange ): Option[LongRange] = {
    val ( istart, iend ) = ( Math.max( start, other.start ),  Math.min( end, other.end ) )
    if ( istart <= iend ) Some( new LongRange( istart, iend ) ) else None
  }
  def disjoint( other: LongRange ) = ( end < other.start ) || ( other.end < start )
  def merge( other: LongRange ): Option[LongRange] = if( disjoint(other) ) None else Some( union(other) )
  def union( other: LongRange ): LongRange = new LongRange( Math.min( start, other.start ), Math.max( end, other.end ) )
  def +( that: LongRange ): LongRange = {
    if( end != that.start ) { throw new Exception( s"Attempt to concat non-contiguous ranges: first = ${toString} <-> second = ${that.toString}" )}
    LongRange( start, that.end )
  }
  def contains( loc: Long ): Boolean = ( loc >= start ) && ( loc < end )
  def locate( loc: Long ): Int = if ( loc < start ) -1 else if ( loc >= end ) 1 else 0
  def print( implicit strRep: StrRep ) = s"${strRep(start)}<->${strRep(end)}"
  override def toString = print
}

object PartitionKey {
  def apply( start: Long, end: Long, elemStart: Int, numElems: Int ): PartitionKey = new PartitionKey( start, end, elemStart, numElems )

  def apply( ranges: Iterable[PartitionKey] ): PartitionKey = {
    val startMS = ranges.foldLeft( Long.MaxValue )( ( tval, key ) => Math.min( tval, key.start ) )
    val endMS = ranges.foldLeft( Long.MinValue )( ( tval, key ) => Math.max( tval, key.end) )
    val nElems = ranges.foldLeft( 0 )( ( tval, key ) => tval + key.numElems )
    new PartitionKey( startMS, endMS, ranges.head.elemStart, nElems )
  }
  implicit val strRep: LongRange.StrRep = _.toString

}

class PartitionKey( start: Long, end: Long, val elemStart: Int, val numElems: Int ) extends LongRange( start, end ) with Ordered[PartitionKey] with Serializable {
  override def equals(other: Any): Boolean = other match {
    case tp: PartitionKey => ( tp.start == start) && ( tp.end == end) && ( tp.numElems == numElems) && ( tp.elemStart == elemStart )
    case lr: LongRange => ( lr.start == start ) && ( lr.end == end )
    case _ => false
  }
  def sameRange( lr: LongRange ): Boolean = ( lr.start == start ) && ( lr.end == end )
  def estElemIndexAtLoc( loc: Long ): Int =  ( elemStart + getRelPos(loc) * numElems ).toInt
  def compare( that: PartitionKey ): Int = start.compare( that.start )
  def elemRange: (Int,Int) = ( elemStart, elemStart + numElems )
  def +( that: PartitionKey ): PartitionKey = {
    if( end != that.start ) { throw new Exception( s"Attempt to concat non-contiguous ranges: first = ${toString} <-> second = ${that.toString}" )}
    PartitionKey( start, that.end, elemStart, numElems + that.numElems )
  }
  def startPoint: PartitionKey  = PartitionKey( start, start, elemStart, 0 )
}

object RangePartitioner {
  def apply( partitions: Map[Int,PartitionKey] ): RangePartitioner = {
    new RangePartitioner( partitions )
  }
  def apply( ranges: Iterable[PartitionKey] ): RangePartitioner = {
    val indexed_ranges = ranges.zipWithIndex map { case (range, index) => index -> range }
    new RangePartitioner( Map( indexed_ranges.toSeq: _* ) )
  }
//  def apply( range: LongRange, nParts: Int ): RangePartitioner = {
//    val psize: Double = range.size / nParts
//    val parts = Map( ( 0 until nParts ) map (index => index -> LongRange( ( range.start + index * psize ).toLong, ( range.start + ( index + 1 ) * psize ).toLong ) ): _* )
//    new RangePartitioner(parts)
//  }

}
// implicit val strRep: LongRange.StrRep = CalendarDate.of(_).toString

class RangePartitioner( val partitions: Map[Int,PartitionKey] ) extends Partitioner with Loggable {
  val range = PartitionKey(partitions.values)
  val numParts = partitions.size
  val numElems = range.numElems
  val psize: Double = range.size / numParts
  override def numPartitions: Int = numParts

  def getCoordRangeMap = {
    val startIndices =  partitions.mapValues( _.elemRange )
    startIndices
  }

  def findPartIndex( index: Int, loc: Long ): Int = partitions.get(index) match {
    case None => -1
    case Some( key ) => key.locate( loc ) match {
      case 0 => index
      case x => findPartIndex( index + x, loc )
    }
  }

  def estPartIndexAtLoc( loc: Long ): Int = ( range.getRelPos(loc) * numParts ).toInt

  def colaesce: RangePartitioner = RangePartitioner( List( range ) )

  def getPartIndexFromLocation( loc: Long ) = findPartIndex( estPartIndexAtLoc( loc ), loc )

  override def getPartition( key: Any ): Int = {
    val index: Int = key match {
      case rkey: LongRange => getPartIndexFromLocation(rkey.center)
      case wtf => throw new Exception( "Illegal partition key type: " + key.getClass.getName )
    }
    if( index >= numParts ) throw new Exception( s"Illegal index value: $index out of $numParts for key ${key.toString}" )
    if( index < 0 ) throw new Exception( s"Can't find partition index for key ${key.toString}" )
    index
  }

  def newPartitionKey( irange: LongRange ): Option[PartitionKey] = irange.intersect( range ) flatMap ( keyrange => partitions.get( getPartIndexFromLocation(keyrange.center) ) )
  def newPartitionKeyOpt( location: Long ): Option[PartitionKey] = partitions.get( getPartIndexFromLocation(location) )
  def newPartitionKey( location: Long ): PartitionKey = partitions.get( getPartIndexFromLocation(location) ) getOrElse( throw new Exception( s"Location of new key ${location} is out of bounds for partitioner"))
}

//object PartitionManager {
//
//  def getPartitioner( rdd: RDD[(LongRange,RDDPartition)], nParts: Int = -1 ): RangePartitioner = {
//    rdd.partitioner match {
//      case Some( partitioner ) => partitioner match {
//        case range_partitioner: RangePartitioner => if( nParts > 0 ) range_partitioner.repartition(nParts) else range_partitioner
//        case wtf => throw new Exception( "Found partitioner of wrong type: " + wtf.getClass.getName )
//      }
//      case None => throw new Exception( "Missing partitioner for rdd"  )
//    }
//  }
//}



//object partTest extends App {
//  val nParts = 17
//  val nItems = 20
//  val partitioner = new IndexPartitioner( nItems, nParts )
//  (0 until nItems) foreach  { index => println( s" $index -> ${partitioner.getPartition(index)}" ) }
//}

