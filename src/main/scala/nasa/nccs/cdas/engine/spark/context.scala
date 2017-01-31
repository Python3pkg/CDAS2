package nasa.nccs.cdas.engine.spark

import java.nio.file.Paths

import nasa.nccs.caching.{CDASPartitioner, Partition, Partitions}
import nasa.nccs.cdapi.cdm.{CDSVariable, OperationInput, OperationTransientInput, PartitionedFragment}
import nasa.nccs.cdapi.data.{HeapFltArray, RDDPartSpec, RDDPartition, RDDVariableSpec}
import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.cdas.engine.WorkflowNode
import nasa.nccs.cdas.utilities.appParameters
import nasa.nccs.esgf.process._
import nasa.nccs.utilities.Loggable
import org.apache.spark.rdd.RDD
import org.apache.spark.{RangePartitioner, SparkConf, SparkContext}
import ucar.ma2
import ucar.nc2.dataset.CoordinateAxis1DTime

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

object CDSparkContext extends Loggable {
  val kyro_buffer_mb = 24
  val kyro_buffer_max_mb = 300
  val default_master = "local[%d]".format(CDASPartitioner.nProcessors)

  def apply( master: String=default_master, appName: String="CDAS", logConf: Boolean = false, enableMetrics: Boolean = false ) : CDSparkContext = {
    logger.info( "--------------------------------------------------------")
    logger.info( "   ****  NEW CDSparkContext Created  **** ")
    logger.info( "--------------------------------------------------------\n\n")

    val sparkContext = new SparkContext( getSparkConf(master, appName, logConf, enableMetrics) )
    sparkContext.setLogLevel("WARN")
    val rv = new CDSparkContext( sparkContext )

    logger.info( "--------------------------------------------------------")
    logger.info( "   ****  CDSparkContext Creation FINISHED  **** ")
    logger.info( "--------------------------------------------------------")
    rv
  }

  def apply( conf: SparkConf ) : CDSparkContext = new CDSparkContext( new SparkContext(conf) )
  def apply( context: SparkContext ) : CDSparkContext = new CDSparkContext( context )
  def apply( url: String, name: String ) : CDSparkContext = new CDSparkContext( new SparkContext( getSparkConf( url, name, false, false ) ) )

  def merge( rdd0: RDD[(Int,RDDPartition)], rdd1: RDD[(Int,RDDPartition)] ): RDD[(Int,RDDPartition)] = rdd0.join(rdd1).map { case ( index, (r0, r1) ) => ( index, r0 ++ r1) }
  def append( p0: (Int,RDDPartition), p1: (Int,RDDPartition) ): (Int,RDDPartition) = ( p0._1, p0._2.append(p1._2) )

  def getSparkConf( master: String, appName: String, logConf: Boolean, enableMetrics: Boolean  ) = {
    val sc = new SparkConf(false)
      .setMaster( master )
      .setAppName( appName )
      .set("spark.logConf", logConf.toString )
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer") //
      .set("spark.kryoserializer.buffer",kyro_buffer_mb.toString)
      .set("spark.kryoserializer.buffer.max",kyro_buffer_max_mb.toString)
    if( enableMetrics ) sc.set("spark.metrics.conf", getClass.getResource("/spark.metrics.properties").getPath )
    sc
  }
}

class CDSparkContext( @transient val sparkContext: SparkContext ) extends Loggable {

  def setLocalProperty(key: String, value: String): Unit = {
    sparkContext.setLocalProperty(key, value)
  }

  def getConf: SparkConf = sparkContext.getConf

  def coalesce( rdd: RDD[(Int,RDDPartition)], nItems: Int ): RDD[(Int,RDDPartition)] = {
    var repart_rdd = rdd repartitionAndSortWithinPartitions new IndexPartitioner ( nItems, 1 )
    val result_rdd = repart_rdd glom() map ( _.fold ((0,RDDPartition.empty)) ((x,y) => { (x._1,x._2.append(y._2)) } ) )
    result_rdd
  }

  def cacheRDDPartition( partFrag: PartitionedFragment ): RDD[RDDPartition] = {
    val nPart = partFrag.partitions.parts.length
    val indexRDD: RDD[Int] = sparkContext.makeRDD( 0 to nPart-1, nPart )
    indexRDD.map( iPart => partFrag.partRDDPartition( iPart ) )
  }

  def getPartitions( opInputs: Iterable[OperationInput] ): Option[Partitions] = {
    for( opInput <- opInputs ) opInput match {
      case pfrag: PartitionedFragment => return Some( pfrag.partitions )
      case _ => None
    }
    None
  }

  def getRDD( uid: String, pFrag: PartitionedFragment, requestCx: RequestContext, opSection: Option[ma2.Section], node: WorkflowNode ): RDD[(Int,RDDPartition)] = {
    val partitions = pFrag.partitions
    val rddSpecs: Array[RDDPartSpec] = partitions.parts map ( partition =>
      RDDPartSpec( partition, List(pFrag.getRDDVariableSpec(uid, partition, opSection) ) )
      ) filterNot( _.empty(uid) )
    val nItems = rddSpecs.length
    logger.info( "Discarded empty partitions: Creating RDD with <<%d>> items".format( nItems ) )
    if( nItems == 0 ) throw new Exception( "Invalid RDD: all partitions are empty: " + uid )
    val startIndex = rddSpecs.map( _.partition.index ).reduceLeft( _ min _ )
    val parallelized_rddspecs = sparkContext parallelize(rddSpecs) keyBy ( _.partition.index - startIndex ) partitionBy( new IndexPartitioner( nItems, nItems ) )
    val parallelized_result =  parallelized_rddspecs mapValues ( spec => spec.getRDDPartition ) sortByKey(true)
    val parallelize = node.getKernelOption("parallelize","true").toBoolean
    if( parallelize ) { parallelized_result persist } else { coalesce (parallelized_result,nItems) persist }
  }

 /* def inputConversion( dataInput: PartitionedFragment, targetGrid: TargetGrid ): PartitionedFragment = {
    dataInput.fragmentSpec.targetGridOpt match {
      case Some(inputTargetGrid) =>
        val inputTimeAxis: CoordinateAxis1DTime = inputTargetGrid.grid.getAxisSpec( 0 ).getTimeAxis
        val timeAxis: CoordinateAxis1DTime = targetGrid.grid.getAxisSpec( 0 ).getTimeAxis
        if( inputTimeAxis.equals(timeAxis) ) dataInput
        else dataInput.timeConversion( timeAxis )
      case None =>
        throw new Exception("Missing target grid for fragSpec: " + dataInput.fragmentSpec.toString)
    }
  }*/

  def getRDD( uid: String, tVar: OperationTransientInput, partitions: Partitions, opSection: Option[ma2.Section] ): RDD[(Int,RDDPartition)] = {
    val rddParts: IndexedSeq[(Int,RDDPartition)] = partitions.parts.indices.map( index => index -> RDDPartition( index, tVar.variable.result ) )
//    log( " Create RDD, rddParts = " + rddParts.map(_.toXml.toString()).mkString(",") )
    logger.info( "Creating Transient RDD with <<%d>> paritions".format( rddParts.length ) )
    sparkContext.parallelize(rddParts)
  }


/*
  def domainRDDPartition( opInputs: Map[String,OperationInput], context: CDASExecutionContext): RDD[(Int,RDDPartition)] = {
    val opSection: Option[ma2.Section] = context.getOpSectionIntersection
    val rdds: Iterable[RDD[(Int,RDDPartition)]] = getPartitions(opInputs.values) match {
      case Some(partitions) => opInputs.map {
          case (uid: String, pFrag: PartitionedFragment) =>
            getRDD( uid, pFrag, partitions, opSection )
          case (uid: String, tVar: OperationTransientInput ) =>
            getRDD( uid, tVar, partitions, opSection )
          case (uid, x ) => throw new Exception( "Unsupported OperationInput class: " + x.getClass.getName )
        }
      case None => opInputs.map {
          case (uid: String, tVar: OperationTransientInput ) => sparkContext.parallelize(List(0)).map(index => index -> tVar.variable.result )
          case (uid, x ) => throw new Exception( "Unsupported OperationInput class: " + x.getClass.getName )
        }
    }
    if( opInputs.size == 1 ) rdds.head else rdds.tail.foldLeft( rdds.head )( CDSparkContext.merge(_,_) )
  }
  */

}

