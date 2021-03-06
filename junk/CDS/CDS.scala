//package nasa.nccs.cds2.modules.CDSpark
//
//import nasa.nccs.cdapi.kernels._
//import nasa.nccs.cdapi.tensors.CDFloatArray._
//import nasa.nccs.cdapi.tensors.{CDCoordMap, CDFloatArray, CDTimeCoordMap}
//import nasa.nccs.esgf.process.DataFragment
//import nasa.nccs.utilities.cdsutils
//import nasa.nccs.wps.{ WPSDataInput, WPSProcessOutput }
//
//class max extends SingularKernel {
//  val inputs = List( WPSDataInput("input variable", 1, 1 ) )
//  val outputs = List( WPSProcessOutput( "operation result" ) )
//  val title = "Space/Time Maximum"
//  val description = "Computes maximum element value from input variable data over specified axes and roi"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some((x, y) => { math.max(x, y) })
//  override val reduceCombineOpt = mapCombineOpt
//  override val initValue: Float = -Float.MaxValue
//}
//
//class const extends SingularKernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Sets Input Fragment to constant value"
//
//  override def map(partIndex: Int, inputs: List[Option[DataFragment]], context: KernelContext): Option[DataFragment] = {
//    inputs.head.map(dataFrag => {
//      val axes: AxisIndices = context.grid.getAxisIndices(context.config("axes", ""))
//      val async = context.config("async", "false").toBoolean
//      val resultFragSpec = dataFrag.getReducedSpec(axes)
//      val sval = context.config("value", "1.0")
//      val t10 = System.nanoTime
//      val result_val_masked: CDFloatArray = (dataFrag.data := sval.toFloat)
//      val t11 = System.nanoTime
//      logger.info("Constant op, time = %.4f s, result sample = %s".format((t11 - t10) / 1.0E9, getDataSample(result_val_masked).mkString(",").toString))
//      DataFragment(resultFragSpec, result_val_masked)
//    })
//  }
//}
//
//class min2 extends DualKernel {
//  val inputs = List(Port("input fragment", "2"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Element-wise minimum of two Input Fragments"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some(minOp)
//}
//
//class max2 extends DualKernel {
//  val inputs = List(Port("input fragment", "2"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Element-wise maximum of two Input Fragments"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some(maxOp)
//}
//
//class sum2 extends DualKernel {
//  val inputs = List(Port("input fragment", "2"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Element-wise sum of two Input Fragments"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some(addOp)
//}
//
//class diff2 extends DualKernel {
//  val inputs = List(Port("input fragment", "2"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Element-wise difference of two Input Fragments"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some(subtractOp)
//}
//
//class mult2 extends DualKernel {
//  val inputs = List(Port("input fragment", "2"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Element-wise multiplication of two Input Fragments"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some(multiplyOp)
//}
//
//class div2 extends DualKernel {
//  val inputs = List(Port("input fragment", "2"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Element-wise division of two Input Fragments"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some(divideOp)
//}
//
//
//class min extends SingularKernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Minimum over Axes on Input Fragment"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some((x, y) => {
//    math.min(x, y)
//  })
//  override val reduceCombineOpt = mapCombineOpt
//  override val initValue: Float = Float.MaxValue
//
//}
//
//class sum extends SingularKernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Sum over Axes on Input Fragment"
//  override val mapCombineOpt: Option[ReduceOpFlt] = Some((x, y) => {
//    x + y
//  })
//  override val reduceCombineOpt = mapCombineOpt
//  override val initValue: Float = 0f
//}
//
//class average extends SingularKernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Weighted Average over Axes on Input Fragment"
//
//  override def map(partIndex: Int, inputs: List[Option[DataFragment]], context: KernelContext): Option[DataFragment] = {
//    inputs.head.map(dataFrag => {
//      val async = context.config("async", "false").toBoolean
//      val axes: AxisIndices = context.grid.getAxisIndices(context.config("axes", ""))
//      val resultFragSpec = dataFrag.getReducedSpec(axes)
//      val t10 = System.nanoTime
//      val weighting_type = context.config("weights", if (context.config("axes", "").contains('y')) "cosine" else "")
//      val weights: CDFloatArray = weighting_type match {
//        case "cosine" =>
//          context.grid.getAxisData('y', dataFrag.spec.cdsection) match {
//            case Some(axis_data) => dataFrag.data.computeWeights(weighting_type, Map('y' -> axis_data))
//            case None => logger.warn("Can't access AxisData for variable %s => Using constant weighting.".format(dataFrag.spec.varname)); dataFrag.data := 1f
//          }
//        case x =>
//          if (!x.isEmpty) {
//            logger.warn("Can't recognize weighting method: %s => Using constant weighting.".format(x))
//          }
//          dataFrag.data := 1f
//      }
//      val (weighted_value_sum_masked, weights_sum_masked) = dataFrag.data.weightedReduce(CDFloatArray.getOp("add"), axes.args, 0f, Some(weights), None)
//      val t11 = System.nanoTime
//      logger.info("Mean_val_masked, time = %.4f s, reduction dims = (%s), sample weighted_value_sum = %s".format((t11 - t10) / 1.0E9, axes.args.mkString(","), getDataSample(weighted_value_sum_masked).mkString(",")))
//      DataFragment(resultFragSpec, weighted_value_sum_masked, weights_sum_masked)
//    })
//  }
//
//  override def combine(context: KernelContext)(a0: DataFragment, a1: DataFragment, axes: AxisIndices): DataFragment = weightedValueSumCombiner(context)(a0, a1, axes)
//
//  override def postOp(result: DataFragment, context: KernelContext): DataFragment = weightedValueSumPostOp(result, context)
//
//}
//
//class subset extends Kernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Subset of Input Fragment"
//}
//
//class timeBin extends Kernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Aggregate data into bins using specified reduce function"
//
//  override def map(partIndex: Int, inputs: List[Option[DataFragment]], context: KernelContext): Option[DataFragment] = {
//    inputs.head.map(dataFrag => {
//      val async = context.config("async", "false").toBoolean
//      val optargs: Map[String, String] = context.getConfiguration
//      val axes: AxisIndices = context.grid.getAxisIndices(context.config("axes", ""))
//
//      val period = getIntArg(optargs, "period", Some(1))
//      val mod = getIntArg(optargs, "mod", Some(12))
//      val unit = getStringArg(optargs, "unit", Some("month"))
//      val offset = getIntArg(optargs, "offset", Some(0))
//      logger.info("timeBin, input shape = [ %s ], roi = [ %s ]".format(dataFrag.data.getShape.mkString(","), dataFrag.spec.roi.toString))
//
//      val t10 = System.nanoTime
//      val cdTimeCoordMap: CDTimeCoordMap = new CDTimeCoordMap(context.grid, dataFrag.spec.roi)
//      val coordMap: CDCoordMap = cdTimeCoordMap.getMontlyBinMap(dataFrag.spec.roi)
//      //  val coordMap: CDCoordMap = cdTimeCoordMap.getTimeCycleMap(period, unit, mod, offset)
//      val timeData = cdTimeCoordMap.getTimeIndexIterator("month", dataFrag.spec.roi.getRange(0)).toArray
//      //        logger.info("Binned array, timeData = [ %s ]".format(timeData.mkString(",")))
//      //        logger.info("Binned array, coordMap = %s".format(coordMap.toString))
//      //        logger.info("Binned array, dates = %s".format(cdTimeCoordMap.getDates.mkString(", ")))
//      //        logger.info("Binned array, input data = %s".format(dataFrag.data.toDataString))
//      dataFrag.data.weightedReduce(CDFloatArray.getOp("add"), axes.args, 0f, None, Some(coordMap)) match {
//        case (values_sum: CDFloatArray, weights_sum: CDFloatArray) =>
//          val t11 = System.nanoTime
//          //            logger.info("Binned array, time = %.4f s, section = %s\n *** values = %s\n *** weights=%s".format((t11 - t10) / 1.0E9, dataFrag.spec.roi.toString, values_sum.toDataString, weights_sum.toDataString ))
//          //            val resultFragSpec = dataFrag.getReducedSpec(Set(axes.args(0)), values_sum.getShape(axes.args(0)))
//          logger.info("timeBin, result shape = [ %s ], result spec = %s".format(values_sum.getShape.mkString(","), dataFrag.spec.toString))
//          DataFragment(dataFrag.spec, values_sum, weights_sum, coordMap)
//      }
//    })
//  }
//
//  override def combine(context: KernelContext)(a0: DataFragment, a1: DataFragment, axes: AxisIndices): DataFragment = weightedValueSumCombiner(context)(a0, a1, axes)
//
//  override def postOp(result: DataFragment, context: KernelContext): DataFragment = weightedValueSumPostOp(result, context)
//}
//
////  class createV extends Kernel {
////    val inputs = List(Port("input fragment", "1"))
////    val outputs = List(Port("result", "1"))
////    override val description = "Aggregate data into bins using specified reduce function"
////
////    def mapReduce1( inputs: List[PartitionedFragment], context: OperationContext, nprocs: Int ): Future[Option[DataFragment]] = {
////      val future_results: IndexedSeq[Future[Option[DataFragment]]] = (0 until nprocs).map( iproc => Future { map(iproc,inputs,context) } )
////      reduce( future_results, context )
////    }
////
////    override def executeProcess( context: OperationContext, nprocs: Int  ): WPSResponse = {
////      val t0 = System.nanoTime()
////      val inputs: List[PartitionedFragment] = inputVars( context )
////      var opResult1: Future[Option[DataFragment]] = mapReduce1( inputs, context, nprocs )
////      opResult1.onComplete {
////        case Success(dataFragOpt) =>
////          logger.info(s"********** Completed Execution of Kernel[$name($id)]: %s , total time = %.3f sec  ********** \n".format(context.toString, (System.nanoTime() - t0) / 1.0E9))
////        case Failure(t) =>
////          logger.error(s"********** Failed Execution of Kernel[$name($id)]: %s ********** \n".format(context.toString ))
////          logger.error( " ---> Cause: " + t.getCause.getMessage )
////          logger.error( "\n" + t.getCause.getStackTrace.mkString("\n") + "\n" )
////      }
////      val timeBinResult = postOp( opResult1, context  )
////      createResponse( timeBinResult, inputs, context )
////    }
////    def postOp( future_result: Future[Option[DataFragment]], context: OperationContext ):  Future[Option[DataFragment]] = future_result
////    override def reduce( future_results: IndexedSeq[Future[Option[DataFragment]]], context: OperationContext ):  Future[Option[DataFragment]] = Future.reduce(future_results)(reduceOp(context) _)
////
////    override def map( partIndex: Int, inputs: List[PartitionedFragment], context: OperationContext ): Option[DataFragment] = {
////      val inputVar: PartitionedFragment = inputs.head
////      logger.info( " ***timeBin*** inputVar FragSpec=(%s) ".format( inputVar.fragmentSpec.toString ) )
////      inputVar.domainDataFragment(partIndex,context) map { dataFrag =>
////        val async = context.config("async", "false").toBoolean
////        val optargs: Map[String, String] = context.getConfiguration
////        val axes: AxisIndices = context.getAxisIndices(context.config("axes", ""))
////
////        val period = getIntArg(optargs, "period", Some(1) )
////        val mod = getIntArg(optargs, "mod",  Some(12) )
////        val unit = getStringArg(optargs, "unit",  Some("month") )
////        val offset = getIntArg(optargs, "offset", Some(0) )
////
////        val t10 = System.nanoTime
////        val cdTimeCoordMap: CDTimeCoordMap = new CDTimeCoordMap(context.targetGrid)
////        val coordMap: CDCoordMap = cdTimeCoordMap.getTimeCycleMap(period, unit, mod, offset)
////        val timeData = cdTimeCoordMap.getTimeIndexIterator("month").toArray
////        logger.info("Binned array, timeData = [ %s ]".format(timeData.mkString(",")))
////        logger.info("Binned array, coordMap = %s".format(coordMap.toString))
////        logger.info("Binned array, input shape = %s, spec=%s".format( dataFrag.data.getShape.mkString(","), dataFrag.spec.toString ) )
////        dataFrag.data.weightedReduce( CDFloatArray.getOp("add"), axes.args, 0f, None, Some(coordMap)) match {
////          case (values_sum: CDFloatArray, weights_sum: CDFloatArray) =>
////            val t11 = System.nanoTime
////            logger.info("Binned array, time = %.4f s, result sample = %s".format((t11 - t10) / 1.0E9, getDataSample(values_sum).mkString(",")))
////            val resultFragSpec = dataFrag.getReducedSpec(Set(axes.args(0)), values_sum.getShape(axes.args(0)))
////            new DataFragment(resultFragSpec, values_sum, Some(weights_sum) )
////        }
////      }
////    }
////    override def combine(context: OperationContext)(a0: DataFragment, a1: DataFragment, axes: AxisIndices ): DataFragment =  weightedValueSumCombiner(context)(a0, a1, axes )
////    override def postOp( future_result: Future[Option[DataFragment]], context: OperationContext ):  Future[Option[DataFragment]] = {
////      val timeBinResult = weightedValueSumPostOp( future_result, context )
////      val anomalyArray = CDFloatArray.combine( CDFloatArray.subtractOp, dataFrag.data, timeBinResult, coordMap )
////      val anomalyResult = new DataFragment(resultFragSpec, anomalyArray )
////      timeBinResult
////    }
////  }
//
//class anomaly extends SingularKernel {
//  val inputs = List(Port("input fragment", "1"))
//  val outputs = List(Port("result", "1"))
//  override val description = "Anomaly over Input Fragment"
//
//  override def map(partIndex: Int, inputs: List[Option[DataFragment]], context: KernelContext): Option[DataFragment] = {
//    inputs.head.map(dataFrag => {
//      val async = context.config("async", "false").toBoolean
//      val axes: AxisIndices = context.grid.getAxisIndices(context.config("axes", ""))
//      val resultFragSpec = dataFrag.getReducedSpec(axes)
//      val t10 = System.nanoTime
//      val weighting_type = context.config("weights", if (context.config("axis", "").contains('y')) "cosine" else "")
//      val weightsOpt: Option[CDFloatArray] = weighting_type match {
//        case "" => None
//        case wtype => context.grid.getAxisData('y', dataFrag.spec.cdsection).map(axis_data => dataFrag.data.computeWeights(wtype, Map('y' -> axis_data)))
//      }
//      val anomaly_result: CDFloatArray = dataFrag.data.anomaly(axes.args, weightsOpt)
//      logger.info("Partition[%d], generated anomaly result: %s".format(partIndex, anomaly_result.toDataString))
//      val t11 = System.nanoTime
//      DataFragment(resultFragSpec, anomaly_result)
//    })
//  }
//}
