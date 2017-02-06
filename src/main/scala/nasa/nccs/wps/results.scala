package nasa.nccs.wps

import nasa.nccs.cdapi.data.RDDPartition
import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.cdas.utilities.appParameters
import nasa.nccs.esgf.process.{DataFragmentSpec, TargetGrid}
import nasa.nccs.utilities.Loggable

object WPSExecuteResponse {
  def merge(  serviceInstance: String, responses: List[WPSExecuteResponse] ): WPSExecuteResponse = new MergedWPSExecuteResponse( serviceInstance, responses )
}

object ResponseSyntax extends Enumeration {
  val WPS, Generic = Value
}

trait WPSResponse {
  def toXml( syntax: ResponseSyntax.Value ): xml.Elem
}

abstract class WPSExecuteResponse( val serviceInstance: String, val processes: List[WPSProcess] ) extends WPSResponse {
  val proxyAddress =  appParameters("wps.server.proxy.href","")
  def this( serviceInstance: String, process: WPSProcess ) = this( serviceInstance, List(process) )
  def getReference(syntax: ResponseSyntax.Value): xml.Elem
  def getFileReference(syntax: ResponseSyntax.Value): xml.Elem
  def getResultReference(syntax: ResponseSyntax.Value): xml.Elem

  def toXml(syntax: ResponseSyntax.Value): xml.Elem = syntax match {
    case ResponseSyntax.WPS =>
      <wps:ExecuteResponse xmlns:wps="http://www.opengis.net/wps/1.0.0" xmlns:ows="http://www.opengis.net/ows/1.1" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.opengis.net/wps/1.0.0 ../wpsExecute_response.xsd" service="WPS" version="1.0.0" xml:lang="en-CA" serviceInstance={serviceInstance} statusLocation={proxyAddress}>
        {processes.map(_.ExecuteHeader)}<wps:Status>
        <wps:ProcessStarted>CDAS Process executing</wps:ProcessStarted>
      </wps:Status>
        <wps:ProcessOutputs>
          {getOutputs(syntax)}
        </wps:ProcessOutputs>
      </wps:ExecuteResponse>
    case ResponseSyntax.Generic =>
      <response serviceInstance={serviceInstance} statusLocation={proxyAddress}>
        {processes.map(_.ExecuteHeader)}<status>
        <wps:ProcessStarted>CDAS Process executing</wps:ProcessStarted>
      </status>
        <outputs>
          {getOutputs(syntax)}
        </outputs>
      </response>
  }

  def getOutputs(syntax: ResponseSyntax.Value): List[xml.Elem] = syntax match {
    case ResponseSyntax.Generic =>
      processes.flatMap(p => p.outputs.map(output => <output>
        {output.getHeader(syntax)}{getReference(syntax)}{getFileReference(syntax)}{getResultReference(syntax)}{getProcessOutputs(syntax,p.identifier, output.identifier)}
      </output>))
    case ResponseSyntax.WPS =>
      processes.flatMap(p => p.outputs.map(output => <wps:Output>
        {output.getHeader(syntax)}{getReference(syntax)}{getFileReference(syntax)}{getResultReference(syntax)}{getProcessOutputs(syntax,p.identifier, output.identifier)}
      </wps:Output>))
  }

  def getProcessOutputs(syntax: ResponseSyntax.Value, process_id: String, output_id: String ): Iterable[xml.Elem]

  def getData( syntax: ResponseSyntax.Value, id: String, array: CDFloatArray, units: String, maxSize: Int = Int.MaxValue ): xml.Elem = syntax match {
    case ResponseSyntax.WPS =>
      <wps:Data id={id}>
        <wps:LiteralData uom={units} shape={array.getShape.mkString(",")}>  {array.mkBoundedDataString(",", maxSize)}  </wps:LiteralData>
      </wps:Data>
    case ResponseSyntax.Generic =>
      <data id={id} uom={units} shape={array.getShape.mkString(",")}>  {array.mkBoundedDataString(",", maxSize)}  </data>
  }

}

abstract class WPSReferenceExecuteResponse( serviceInstance: String, val process: WPSProcess, val resultId: String )  extends WPSExecuteResponse( serviceInstance, process )  {
  val statusHref: String = proxyAddress + s"/wps/status?id=$resultId"
  val fileHref: String = proxyAddress + s"/wps/file?id=$resultId"
  val resultHref: String = proxyAddress + s"/wps/result?id=$resultId"
  def getReference( syntax: ResponseSyntax.Value ): xml.Elem = syntax match {
    case ResponseSyntax.WPS => <wps:Reference encoding="UTF-8" mimeType="text/xml" href={statusHref}/>
    case ResponseSyntax.Generic => <reference href={statusHref}/>
  }
  def getFileReference( syntax: ResponseSyntax.Value ): xml.Elem = syntax match {
    case ResponseSyntax.WPS => <wps:Reference encoding="UTF-8" mimeType="text/xml" href={fileHref}/>
    case ResponseSyntax.Generic =>   <reference href={fileHref}/>
  }
  def getResultReference( syntax: ResponseSyntax.Value ): xml.Elem = syntax match {
    case ResponseSyntax.WPS =>  <wps:Reference encoding="UTF-8" mimeType="text/xml" href={resultHref}/>
    case ResponseSyntax.Generic => <reference href={resultHref}/>
  }
  def getResultId: String = resultId
}

class MergedWPSExecuteResponse( serviceInstance: String, responses: List[WPSExecuteResponse] ) extends WPSExecuteResponse( serviceInstance, responses.flatMap(_.processes) ) with Loggable {
  val process_ids: List[String] = responses.flatMap( response => response.processes.map( process => process.identifier ) )
  def getReference( syntax: ResponseSyntax.Value ): xml.Elem = responses.head.getReference( syntax )
  def getFileReference( syntax: ResponseSyntax.Value ): xml.Elem = responses.head.getFileReference( syntax )
  def getResultReference( syntax: ResponseSyntax.Value ): xml.Elem = responses.head.getResultReference( syntax )
  if( process_ids.distinct.size != process_ids.size ) { logger.warn( "Error, non unique process IDs in process list: " + processes.mkString(", ") ) }
  val responseMap: Map[String,WPSExecuteResponse] = Map( responses.flatMap( response => response.processes.map( process => ( process.identifier -> response ) ) ): _* )
  def getProcessOutputs( syntax: ResponseSyntax.Value, process_id: String, response_id: String ): Iterable[xml.Elem] = responseMap.get( process_id ) match {
    case Some( response ) => response.getProcessOutputs( syntax, process_id, response_id );
    case None => throw new Exception( "Unrecognized process id: " + process_id )
  }
}

class RDDExecutionResult( serviceInstance: String, process: WPSProcess, id: String, val result: RDDPartition,  resultId: String ) extends WPSReferenceExecuteResponse( serviceInstance, process, resultId )  with Loggable {
  def getProcessOutputs( syntax: ResponseSyntax.Value, process_id: String, output_id: String  ): Iterable[xml.Elem] = {
    result.elements map { case (id, array) => getData( syntax, id, array.toCDFloatArray, array.metadata.getOrElse("units","") ) }
  }
}

class ExecutionErrorReport( serviceInstance: String, process: WPSProcess, id: String, val err: Throwable ) extends WPSReferenceExecuteResponse( serviceInstance, process, "" )  with Loggable {
  print_error
  override def toXml(syntax: ResponseSyntax.Value): xml.Elem = syntax match {
    case ResponseSyntax.WPS =>
      <ows:ExceptionReport xmlns:ows="http://www.opengis.net/ows/1.1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xsi:schemaLocation="http://www.opengis.net/ows/1.1 ../../../ows/1.1.0/owsExceptionReport.xsd" version="1.0.0" xml:lang="en-CA">
        {getReport(syntax)} </ows:ExceptionReport>
    case ResponseSyntax.Generic => <exceptions> {getReport(syntax)} </exceptions>
  }
  def getReport(syntax: ResponseSyntax.Value): Iterable[xml.Elem] =  syntax match {
    case ResponseSyntax.WPS =>
      List(<ows:Exception exceptionCode={err.getClass.getName}> <ows:ExceptionText>  {err.getMessage} </ows:ExceptionText> </ows:Exception>)
    case ResponseSyntax.Generic =>
      List(<exception name={err.getClass.getName}> {err.getMessage} </exception>)
  }
  def print_error = {
    val err1 = if (err.getCause == null) err else err.getCause
    logger.error("\n\n-------------------------------------------\n" + err1.toString + "\n")
    logger.error(  err1.getStackTrace.mkString("\n")  )
    if (err.getCause != null) { logger.error( "\nTriggered at: \n" + err.getStackTrace.mkString("\n") ) }
    logger.error( "\n-------------------------------------------\n\n")
  }
  def getProcessOutputs( syntax: ResponseSyntax.Value, process_id: String, response_id: String ): Iterable[xml.Elem] = Iterable.empty[xml.Elem]
}


abstract class WPSEventReport extends WPSResponse {
  def toXml(syntax: ResponseSyntax.Value): xml.Elem =  <EventReports>  { getReport(syntax) } </EventReports>
  def getReport(syntax: ResponseSyntax.Value): Iterable[xml.Elem]
}

class UtilityExecutionResult( id: String, val report: xml.Elem )  extends WPSEventReport with Loggable {
  def getReport(syntax: ResponseSyntax.Value): Iterable[xml.Elem] =  List( <UtilityReport utilityId={id}>  { report } </UtilityReport> )
}

class WPSExceptionReport( val err: Throwable, serviceInstance: String = "wps" ) extends WPSReferenceExecuteResponse( serviceInstance, process, "" )  with Loggable {
  print_error
  override def toXml(syntax: ResponseSyntax.Value): xml.Elem = syntax match {
    case ResponseSyntax.WPS =>
      <ows:ExceptionReport xmlns:ows="http://www.opengis.net/ows/1.1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xsi:schemaLocation="http://www.opengis.net/ows/1.1 ../../../ows/1.1.0/owsExceptionReport.xsd" version="1.0.0" xml:lang="en-CA">
        {getReport(syntax)} </ows:ExceptionReport>
    case ResponseSyntax.Generic => <exceptions> {getReport(syntax)} </exceptions>
  }
  def getReport(syntax: ResponseSyntax.Value): Iterable[xml.Elem] =  syntax match {
    case ResponseSyntax.WPS =>
      List(<ows:Exception exceptionCode={err.getClass.getName}> <ows:ExceptionText>  {err.getMessage} </ows:ExceptionText> </ows:Exception>)
    case ResponseSyntax.Generic =>
      List(<exception name={err.getClass.getName}> {err.getMessage} </exception>)
  }
  def print_error = {
    val err1 = if (err.getCause == null) err else err.getCause
    logger.error("\n\n-------------------------------------------\n" + err1.toString + "\n")
    logger.error(  err1.getStackTrace.mkString("\n")  )
    if (err.getCause != null) { logger.error( "\nTriggered at: \n" + err.getStackTrace.mkString("\n") ) }
    logger.error( "\n-------------------------------------------\n\n")
  }
  def getProcessOutputs( syntax: ResponseSyntax.Value, process_id: String, response_id: String ): Iterable[xml.Elem] = Iterable.empty[xml.Elem]
}


class AsyncExecutionResult( serviceInstance: String, process: WPSProcess, resultId: String ) extends WPSReferenceExecuteResponse( serviceInstance, process, resultId )  {
  def getProcessOutputs( syntax: ResponseSyntax.Value, process_id: String, output_id: String ): Iterable[xml.Elem] = List()
}

class WPSMergedEventReport( val reports: List[WPSEventReport] ) extends WPSEventReport {
  def getReport(syntax: ResponseSyntax.Value): Iterable[xml.Elem] = reports.flatMap( _.getReport(syntax) )
}

class BlockingExecutionResult( serviceInstance: String, process: WPSProcess, id: String, val intputSpecs: List[DataFragmentSpec], val gridSpec: TargetGrid, val result_tensor: CDFloatArray,
                               resultId: String ) extends WPSReferenceExecuteResponse( serviceInstance, process, resultId )  with Loggable {
  //  def toXml_old = {
  //    val idToks = id.split('-')
  //    logger.info( "BlockingExecutionResult-> result_tensor(" + id + "): \n" + result_tensor.toString )
  //    val inputs = intputSpecs.map( _.toXml )
  //    val grid = gridSpec.toXml
  //    val results = result_tensor.mkDataString(",")
  //    <result id={id} op={idToks.head} rid={resultId.getOrElse("")}> { inputs } { grid } <data undefined={result_tensor.getInvalid.toString}> {results}  </data>  </result>
  //  }
  def getProcessOutputs( syntax: ResponseSyntax.Value, process_id: String, output_id: String  ): Iterable[xml.Elem] = List( getData( syntax, output_id, result_tensor, intputSpecs.head.units, 250 ) )
}

