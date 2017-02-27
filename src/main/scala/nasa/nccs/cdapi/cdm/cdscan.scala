package nasa.nccs.cdapi.cdm

import java.io._
import java.net.{URI, URL}
import java.nio._
import java.nio.file.{FileSystems, Path, Paths}
import java.util.Formatter

import nasa.nccs.cdapi.tensors.CDDoubleArray
import nasa.nccs.cdas.loaders.Collections
import nasa.nccs.cdas.utilities.runtime
import nasa.nccs.utilities.Loggable
import nasa.nccs.utilities.cdsutils
import ucar.nc2.{FileWriter => _, _}
import ucar.{ma2, nc2}
import ucar.nc2.constants.AxisType
import ucar.nc2.dataset._
import ucar.nc2.time.CalendarDate

import collection.mutable.ListBuffer
import scala.collection.JavaConversions._
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.xml.XML

object NCMLWriter extends Loggable {

  def apply( path: File ): NCMLWriter = { new NCMLWriter( Array(path).iterator ) }
  def getName( node: nc2.CDMNode ): String  = node.getShortName
  def isNcFileName( fName: String ): Boolean = { val fname = fName.toLowerCase; fname.endsWith(".nc4") || fname.endsWith(".nc") || fname.endsWith(".hdf") || fname.endsWith(".ncml") }

  def isNcFile( file: File ): Boolean = { file.isFile && isNcFileName( file.getName.toLowerCase ) }

  def isCollectionFile( file: File ): Boolean = {
    val fname = file.getName.toLowerCase
    file.isFile && fname.endsWith(".csv")
  }
  def getCacheDir: String = {
    val collection_file_path = Collections.getCacheFilePath("local_collections.xml")
    new java.io.File( collection_file_path ).getParent.stripSuffix("/")
  }

  def getCachePath( subdir: String ): Path = {  FileSystems.getDefault.getPath( getCacheDir, subdir ) }

  def getNcURIs(file: File): Iterable[URI] = {
      if( isCollectionFile(file) ) {
        val bufferedSource = Source.fromFile(file)
        val entries = for (line <- bufferedSource.getLines; if isNcFileName(line)) yield new URI(line)
        entries.toIterable
      } else {
        getNcFiles(file).map( _.toURI )
      }
  }

  def getNcFiles(file: File): Iterable[File] = {
    try {
      if (isNcFile(file)) {
        Seq( file )
      } else  {
        val children = new Iterable[File] {
          def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
        }
        Seq(file) ++: children.flatMap(getNcFiles) filter NCMLWriter.isNcFile
      }
    } catch {
      case err: NullPointerException =>
        logger.warn("Empty collection directory: " + file.toString)
        Iterable.empty[File]
    }
  }

  def getNcFiles(args: Iterator[File]): Iterator[File] = args.map( (arg: File) => NCMLWriter.getNcFiles(arg)).flatten
  def getNcURIs(args: Iterator[File]): Iterator[URI] = args.map( (arg: File) => NCMLWriter.getNcURIs(arg)).flatten

  def getFileHeaders( files: IndexedSeq[URI], nReadProcessors: Int ): IndexedSeq[FileHeader] = {
    if( files.nonEmpty ) {
      val groupSize = cdsutils.ceilDiv(files.length, nReadProcessors)
      logger.info(" Processing %d files with %d files/group with %d processors".format(files.length, groupSize, nReadProcessors))
      val fileGroups = files.grouped(groupSize).toIndexedSeq
      val fileHeaderFuts = Future.sequence(for (workerIndex <- fileGroups.indices; fileGroup = fileGroups(workerIndex)) yield Future {
        FileHeader.factory(fileGroup, workerIndex)
      })
      Await.result(fileHeaderFuts, Duration.Inf).flatten sortWith { (afr0, afr1) => afr0.startValue < afr1.startValue }
    } else IndexedSeq.empty[FileHeader]
  }
}

//class NCMLSerialWriter(val args: Iterator[String]) {
//  val files: IndexedSeq[File] = NCMLWriter.getNcFiles(args).toIndexedSeq
//  val nFiles = files.length
//  val fileHeaders = NCMLWriter.getFileHeadersSerial(files)
//
//  def getNCML: xml.Node = {
//    <netcdf xmlns="http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2">
//      <attribute name="title" type="string" value="NetCDF aggregated dataset"/>
//      <aggregation dimName="time" units={cdsutils.baseTimeUnits} type="joinExisting">
//        { for( fileHeader <- fileHeaders ) yield { <netcdf location={"file:" + fileHeader.path} ncoords={fileHeader.nElem.toString}> { fileHeader.axisValues.mkString(", ") } </netcdf> } }
//      </aggregation>
//    </netcdf>
//  }
//}

class NCMLWriter(args: Iterator[File], val maxCores: Int = 8) extends Loggable {
  import NCMLWriter._
  private val nReadProcessors = Math.min(Runtime.getRuntime.availableProcessors, maxCores)
  private val files: IndexedSeq[URI] = NCMLWriter.getNcURIs(args).toIndexedSeq
  if( files.isEmpty ) { throw new Exception( "Error, empty collection at: " + args.map( _.getAbsolutePath ).mkString(",") ) }
  private val nFiles = files.length
  val fileHeaders = NCMLWriter.getFileHeaders(files, nReadProcessors)
  val fileMetadata = FileMetadata(files.head)
  val outerDimensionSize: Int = fileHeaders.foldLeft(0)(_ + _.nElem)
  val ignored_attributes = List( "comments" )
  val overwriteTime = fileHeaders.length > 1

  def isIgnored( attribute: nc2.Attribute ): Boolean = { ignored_attributes.contains(getName(attribute)) }
  def getDimName( dim: nc2.Dimension ): String = {
    val dimname = NCMLWriter.getName(dim)
    fileMetadata.coordVars.map( NCMLWriter.getName(_) ).find( vname => ( vname equals dimname ) || (vname.split(':')(0) == dimname.split(':')(0) ) ) match {
      case Some(vname) => vname
      case None => throw new Exception( s"Coordinate variable ${dimname} does not exist")
    }
  }


  def getAttribute(attribute: nc2.Attribute): xml.Node =
    if (attribute.getDataType == ma2.DataType.STRING) {
      if (attribute.getLength > 1) {
        val sarray: IndexedSeq[String] = (0 until attribute.getLength).map(i => attribute.getStringValue(i).filter(ch => org.jdom2.Verifier.isXMLCharacter(ch)))
          <attribute name={getName(attribute) } value={sarray.mkString("|")} separator="|"/>
      } else {
          <attribute name={getName(attribute)} value={attribute.getStringValue(0)}/>
      }
    } else {
      if (attribute.getLength > 1) {
        val sarray: IndexedSeq[String] = (0 until attribute.getLength).map(i => attribute.getNumericValue(i).toString)
          <attribute name={getName(attribute)} type={attribute.getDataType.toString} value={sarray.mkString(" ")}/>
      } else {
          <attribute name={getName(attribute)} type={attribute.getDataType.toString} value={attribute.getNumericValue(0).toString}/>
      }
    }

  def getDims(variable: nc2.Variable): String = variable.getDimensions.map(dim => if (dim.isShared) getDimName(dim) else if (dim.isVariableLength) "*" else dim.getLength.toString).toArray.mkString(" ")

  def getDimension(axis: CoordinateAxis ): Option[xml.Node] = {
    axis match {
      case coordAxis: CoordinateAxis1D =>
        val nElems = if( coordAxis.getAxisType == AxisType.Time ) outerDimensionSize else coordAxis.getSize
        val dimension = coordAxis.getDimension(0)
          val node = <dimension name={getName(dimension)} length={nElems.toString} isUnlimited={dimension.isUnlimited.toString} isVariableLength={dimension.isVariableLength.toString} isShared={dimension.isShared.toString}/>
        Some(node)
      case x =>
        logger.warn( "Multidimensional coord axes not currently supported: " + x.getClass.getName + " for axis " + axis.getNameAndDimensions(true) )
        None
    }
  }

  def getAggDataset(fileHeader: FileHeader, timeRegular: Boolean = false ): xml.Node =
    if( timeRegular || !overwriteTime )
        <netcdf location={fileHeader.filePath} ncoords={fileHeader.nElem.toString}/>
    else
        <netcdf location={fileHeader.filePath} ncoords={fileHeader.nElem.toString} coordValue={fileHeader.axisValues.map( x => "%d".format(x.toLong)).mkString(", ")}/>


  def getVariable( variable: nc2.Variable, timeRegularSpecs: Option[(Double,Double)] ): xml.Node = {
    val axisType = fileMetadata.getAxisType(variable)
    <variable name={getName(variable)} shape={getDims(variable)} type={variable.getDataType.toString}>
      { if( axisType == AxisType.Time )  <attribute name="_CoordinateAxisType" value="Time"/>  <attribute name="units" value={if(overwriteTime) cdsutils.baseTimeUnits else variable.getUnitsString}/>
      else for (attribute <- variable.getAttributes; if( !isIgnored( attribute ) ) ) yield getAttribute(attribute) }
      { if( (axisType != AxisType.Time) && (axisType != AxisType.RunTime) ) variable match {
        case coordVar: CoordinateAxis1D => getData(variable, coordVar.isRegular)
        case _ => getData(variable, false)
      }}
    </variable>
  }

  def getData(variable: nc2.Variable, isRegular: Boolean): xml.Node = {
    val dataArray: Array[Double] = CDDoubleArray.factory(variable.read).getArrayData()
    if (isRegular) {
      <values start={"%.3f".format(dataArray(0))} increment={"%.6f".format(dataArray(1)-dataArray(0))}/>
    } else {
      <values>
        {dataArray.map(dv => "%.3f".format(dv)).mkString(" ")}
      </values>
    }
  }

  def getTimeSpecs: Option[(Long,Long)] = {
    val t0 = fileHeaders.head.startValue
    val dt = if(fileHeaders.head.nElem > 1) { fileHeaders.head.axisValues(1)-fileHeaders.head.axisValues(0) } else { fileHeaders(1).startValue - fileHeaders(0).startValue }
    Some( t0 -> dt )
  }

  def getAggregation(timeRegular: Boolean ): xml.Node = {
    val timeVarName = findTimeVariable match {
      case Some(tvar) => getName(tvar)
      case None => { logger.error( s"Can't find time variable, vars: ${fileMetadata.variables.map( v => getName(v)+ ": " + fileMetadata.getAxisType(v).toString ).mkString(", ")}");  "time" }
    }
    <aggregation dimName={timeVarName} type="joinExisting">  { for (fileHeader <- fileHeaders) yield { getAggDataset(fileHeader, timeRegular) } } </aggregation>
  }

  def findTimeVariable: Option[nc2.Variable] = fileMetadata.coordVars find ( fileMetadata.getAxisType(_) == AxisType.Time )

  def getNCML: xml.Node = {
    val timeRegularSpecs= None // getTimeSpecs
    println("Processing %d files with %d workers".format( nFiles, nReadProcessors) )
    <netcdf xmlns="http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2">
      <explicit/>
      <attribute name="title" type="string" value="NetCDF aggregated dataset"/>

      { for( attribute <- fileMetadata.attributes ) yield getAttribute(attribute) }
      { (for (coordAxis <- fileMetadata.coordinateAxes) yield getDimension(coordAxis)).flatten }
      { for (variable <- fileMetadata.coordVars) yield getVariable( variable, timeRegularSpecs ) }
      { getAggregation( timeRegularSpecs.isDefined ) }

    </netcdf>
  }

//      { for (variable <- fileMetadata.variables) yield getVariable( variable, timeRegularSpecs ) }

  def writeNCML( ncmlFile: File ) = {
    logger.info( "Writing *NCML* File: " + ncmlFile.toString )
    val bw = new BufferedWriter(new FileWriter( ncmlFile ))
    bw.write( getNCML.toString )
    bw.close()
  }

}

object FileHeader extends Loggable {
  val maxOpenAttempts = 1
  val retryIntervalSecs = 10
  def apply( file: URI, timeRegular: Boolean ): FileHeader = {
    val ( axisValues, boundsValues) = FileHeader.getTimeCoordValues(file)
    new FileHeader( file.toString, axisValues, boundsValues, timeRegular )
  }

  def factory( files: IndexedSeq[URI], workerIndex: Int ): IndexedSeq[FileHeader] = {
    var retryFiles = new ListBuffer[URI]()
    val timeRegular = false // getTimeAxisRegularity( files.head )
    val firstPass = for (iFile <- files.indices; file = files(iFile)) yield {
      try {
        val t0 = System.nanoTime()
        val fileHeader = FileHeader( file, timeRegular )
        val t1 = System.nanoTime()
        println("Worker[%d]: Processing file[%d] '%s', start = %s, ncoords = %d, time = %.4f ".format(workerIndex, iFile, file, fileHeader.startDate, fileHeader.nElem, (t1 - t0) / 1.0E9))
        if( (iFile % 5) == 0 ) runtime.printMemoryUsage(logger)
        Some(fileHeader)
      } catch { case err: Exception =>
        logger.error("Worker[%d]: Encountered error Processing file[%d] '%s': '%s'".format(workerIndex, iFile, file, err.toString ) )
        if( (iFile % 10) == 0 ) { logger.error( err.getStackTrace.mkString("\n") )}
        retryFiles += file; None
      }
    }
    val secondPass = for (iFile <- retryFiles.indices; file = retryFiles(iFile)) yield {
      println("Worker[%d]: Reprocessing file[%d] '%s'".format(workerIndex, iFile, file ))
      FileHeader(file,timeRegular)
    }
    firstPass.flatten ++ secondPass
  }

  def getTimeAxisRegularity( ncFile: URI ): Boolean = {
    val ncDataset: NetcdfDataset = openNetCDFFile( ncFile )
    try {
      Option(ncDataset.findCoordinateAxis(AxisType.Time)) match {
        case Some(coordAxis) => coordAxis match {
          case coordAxis: CoordinateAxis1D => coordAxis.isRegular
          case _ => throw new Exception("Multidimensional coord axes not currently supported")
        }
        case None => throw new Exception("ncFile does not have a time axis: " + ncFile )
      }
    } finally { ncDataset.close() }
  }


  def getTimeValues( ncDataset: NetcdfDataset, coordAxis: VariableDS, start_index : Int = 0, end_index : Int = -1, stride: Int = 1 ): ( Array[Long], Array[Long] )  = {
    val timeAxis: CoordinateAxis1DTime = CoordinateAxis1DTime.factory( ncDataset, coordAxis, new Formatter())
    val timeCalValues: List[CalendarDate] = timeAxis.getCalendarDates.toList
    val bounds: Array[Long] = ((0 until timeAxis.getShape(0)) map ( index => timeAxis.getCoordBoundsDate(index) map (_.getMillis/1000) )).toArray.flatten
    ( timeCalValues.map( _.getMillis/1000 ).toArray, bounds )
  }

  def openNetCDFFile(ncFile: URI, attempt: Int = 0): NetcdfDataset = try {
    logger.info( "Opening NetCDF dataset(1) at: " + ncFile )
    NetcdfDataset.openDataset( ncFile.toString )
  } catch {
    case ex: Throwable =>
      val error = if( ex.getCause == null ) ex else ex.getCause
      logger.error( "===> Error opening NetCDF dataset(1) at: " + ncFile + ": " + error )
      logger.error( "\n\t" + error.getStackTrace.mkString("\n\t") + "\n")
      throw ex
  }

  def getTimeCoordValues(ncFile: URI): ( Array[Long], Array[Long] ) = {
    val ncDataset: NetcdfDataset = openNetCDFFile( ncFile )
    Option( ncDataset.findCoordinateAxis( AxisType.Time ) ) match {
      case Some( timeAxis ) =>
        val ( values, bounds ) = getTimeValues( ncDataset, timeAxis )
        ncDataset.close()
        ( values, bounds )
      case None => throw new Exception( "ncFile does not have a time axis: " + ncFile.getRawPath )
    }
  }
}

class DatasetFileHeaders( val aggDim: String, val aggFileMap: Seq[FileHeader] ) {
  def getNElems(): Int = {
    assert( !aggFileMap.isEmpty, "Error, aggregated dataset has no files!" )
    return aggFileMap.head.nElem
  }
  def getAggAxisValues: Array[Long] =
    aggFileMap.foldLeft(Array[Long]()) { _ ++ _.axisValues }
}

class FileHeader( val filePath: String, val axisValues: Array[Long], val boundsValues: Array[Long], val timeRegular: Boolean ) {
  def nElem = axisValues.length
  def startValue: Long = axisValues.headOption.getOrElse( Long.MinValue )
  def startDate: String = CalendarDate.of(startValue).toString
  override def toString: String = " *** FileHeader { path='%s', nElem=%d, startValue=%f startDate=%s} ".format( filePath, nElem, startValue, startDate )
}

object FileMetadata {
  def apply( file: URI ): FileMetadata = new FileMetadata(file)
}

class FileMetadata( val ncFile: URI ) {
  private val ncDataset: NetcdfDataset = FileHeader.openNetCDFFile( ncFile )
  val coordinateAxes = ncDataset.getCoordinateAxes.toList
  val dimensions: List[nc2.Dimension] = ncDataset.getDimensions.toList
  val variables = ncDataset.getVariables.filterNot( _.isCoordinateVariable ).toList
  val coordVars = ncDataset.getVariables.filter( _.isCoordinateVariable ).toList
  val attributes = ncDataset.getGlobalAttributes
  val dimNames = dimensions.map( NCMLWriter.getName(_) )
  def getCoordinateAxis( name: String ): Option[nc2.dataset.CoordinateAxis] = coordinateAxes.find( p =>  NCMLWriter.getName(p).equalsIgnoreCase(name) )

  def getAxisType( variable: nc2.Variable ): AxisType = variable match {
    case coordVar: CoordinateAxis1D => coordVar.getAxisType;
    case _ => AxisType.RunTime
  }
}

//object ncTest {
//  val testFile = new URI("http://esgf.nccs.nasa.gov/thredds/dodsC/CMIP5/NASA/GISS/historical/E2-R_historical_r1i1p1/tas_Amon_GISS-E2-R_historical_r1i1p1_185001-187512.nc")
//  val fm = FileMetadata(testFile)
//  print (fm.dimNames)
//  print (".")
//}