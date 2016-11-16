//
//package nasa.nccs.pyapi
//
//import java.net.URI
//import java.io.File
//
//import nasa.nccs.utilities.Loggable
//
//import scala.collection.mutable.ArrayBuffer
//import scala.collection.JavaConverters._
//import scala.util.Try
//import org.apache.spark.SparkUserAppException
//import org.apache.spark.util.RedirectThread
//
///**
//  * A main class used to launch Python applications. It executes python as a
//  * subprocess and then has it connect back to the JVM to access system properties, etc.
//  */
//object PythonRunner extends Loggable {
//  def main(args: Array[String]) {
//    val pythonFile = args(0)
//    val pyFiles = args(1)
//    val otherArgs = args.slice(2, args.length)
//    val pythonExec = sys.env.getOrElse("CDAS_DRIVER_PYTHON", sys.env.getOrElse("CDAS_PYTHON", "python"))
//
//    // Format python file paths before adding them to the PYTHONPATH
//    val formattedPythonFile = formatPath(pythonFile)
//    val formattedPyFiles = formatPaths(pyFiles)
//
//    // Launch a Py4J gateway server for the process to connect to; this will let it see our
//    // Java system properties and such
//    val gatewayServer = new py4j.GatewayServer(null, 0)
//    val thread = new Thread(new Runnable() {
//      override def run(): Unit = {
//        try { gatewayServer.start() }
//        catch { case ex: Exception => logger.error( "Error starting python: " + ex.toString ) }
//      }
//    })
//    thread.setName("py4j-gateway-init")
//    thread.setDaemon(true)
//    thread.start()
//
//    // Wait until the gateway server has started, so that we know which port is it bound to.
//    // `gatewayServer.start()` will start a new thread and run the server code there, after
//    // initializing the socket, so the thread started above will end as soon as the server is
//    // ready to serve connections.
//    thread.join()
//
//    // Build up a PYTHONPATH that includes the Spark assembly JAR (where this class is), the
//    // python directories in SPARK_HOME (if set), and any files in the pyFiles argument
//    val pathElements = new ArrayBuffer[String]
//    pathElements ++= formattedPyFiles
//    pathElements += PythonUtils.sparkPythonPath
//    pathElements += sys.env.getOrElse("PYTHONPATH", "")
//    val pythonPath = PythonUtils.mergePythonPaths(pathElements: _*)
//
//    // Launch Python process
//    val builder = new ProcessBuilder((Seq(pythonExec, formattedPythonFile) ++ otherArgs).asJava)
//    val env = builder.environment()
//    env.put("PYTHONPATH", pythonPath)
//    // This is equivalent to setting the -u flag; we use it because ipython doesn't support -u:
//    env.put("PYTHONUNBUFFERED", "YES") // value is needed to be set to a non-empty string
//    env.put("PYSPARK_GATEWAY_PORT", "" + gatewayServer.getListeningPort)
//    builder.redirectErrorStream(true) // Ugly but needed for stdout and stderr to synchronize
//    try {
//      val process = builder.start()
//
//      new RedirectThread(process.getInputStream, System.out, "redirect output").start()
//
//      val exitCode = process.waitFor()
//      if (exitCode != 0) {
//        throw new SparkUserAppException(exitCode)
//      }
//    } finally {
//      gatewayServer.shutdown()
//    }
//  }
//
//  /**
//    * Format the python file path so that it can be added to the PYTHONPATH correctly.
//    *
//    * Python does not understand URI schemes in paths. Before adding python files to the
//    * PYTHONPATH, we need to extract the path from the URI. This is safe to do because we
//    * currently only support local python files.
//    */
//  def formatPath(path: String, testWindows: Boolean = false): String = {
//    if (PythonUtils.nonLocalPaths(path, testWindows).nonEmpty) {
//      throw new IllegalArgumentException("Launching Python applications through " +
//        s"spark-submit is currently only supported for local files: $path")
//    }
//    // get path when scheme is file.
//    val uri = Try(new URI(path)).getOrElse(new File(path).toURI)
//    var formattedPath = uri.getScheme match {
//      case null => path
//      case "file" | "local" => uri.getPath
//      case _ => null
//    }
//
//    // Guard against malformed paths potentially throwing NPE
//    if (formattedPath == null) {
//      throw new IllegalArgumentException(s"Python file path is malformed: $path")
//    }
//
//    formattedPath
//  }
//
//  /**
//    * Format each python file path in the comma-delimited list of paths, so it can be
//    * added to the PYTHONPATH correctly.
//    */
//  def formatPaths(paths: String, testWindows: Boolean = false): Array[String] = {
//    Option(paths).getOrElse("")
//      .split(",")
//      .filter(_.nonEmpty)
//      .map { p => formatPath(p, testWindows) }
//  }
//
//}
//