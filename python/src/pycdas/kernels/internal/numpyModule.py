from pycdas.kernels.Kernel import Kernel, KernelSpec
from pycdas.cdasArray import npArray
import numpy as np
import time

class StdKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("std", "Standard Deviation", "Computes the standard deviation of the array elements along the given axes.", parallelize=False ) )  # Temporarily forcing some python kernels to run in serial mode

    def executeOperation( self, task, input ):
        result = input.array.std( axis=self.getAxes(task.metadata), keepdims=True )
        return npArray.createResult( task, input, result )

class MaxKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("max", "Maximum", "Computes the maximun of the array elements along the given axes.", reduceOp="max" ) )

    def executeOperation( self, task, input ):
        return npArray.createResult( task, input, input.array.max( axis=self.getAxes(task.metadata), keepdims=True ) )

class MaxKernelSerial(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("maxSer", "Maximum (Serial)", "Computes the maximun of the array elements along the given axes without parallelization (for testing).", parallelize=False  ) )

    def executeOperation( self, task, input ):
        return npArray.createResult( task, input, input.array.max( axis=self.getAxes(task.metadata), keepdims=True ) )

class MaxKernelCustom(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("maxCustRed", "Maximum (Serial)", "Computes the maximun of the array elements along the given axes without parallelization (for testing).", reduceOp="custom"  )  )

    def executeOperation( self, task, input ):
        return npArray.createResult( task, input, input.array.max( axis=self.getAxes(task.metadata), keepdims=True ) )

    def reduce( self, input0, input1, task ):
        return npArray.createResult( task, input0, np.maximum( input0.array, input1.array ) )

class MinKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("min", "Minimum", "Computes the minimun of the array elements along the given axes.", reduceOp="min" ) )

    def executeOperation( self, task, input ):
        return npArray.createResult( task, input, input.array.min( axis=self.getAxes(task.metadata), keepdims=True ) )


class SumKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("sum", "Sum","Computes the sum of the array elements along the given axes.", reduceOp="sum" ) )
    def executeOperation( self, task, input ):
        self.logger.info( " ------------------------------- SUM KERNEL: Operating on input '{0}', shape = {1}, origin = {2}".format( input.name, input.shape, input.origin ) )
        return npArray.createResult( task, input, input.array.sum( axis=self.getAxes(task.metadata), keepdims=True ) )

class AverageKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("ave", "Average Kernel","Computes the average of the array elements along the given axes.", reduceOp="sumw", postOp="normw", nOutputsPerInput=2 ) )

    def executeOperations(self, task, inputs):
        kernel_inputs = [inputs.get(inputId.split('-')[0]) for inputId in task.inputs]
        if None in kernel_inputs: raise Exception( "ExecuteTask ERROR: required input {0} not available in task inputs: {1}".format(task.inputs, inputs.keys()))
        results = []
        axes = self.getAxes(task.metadata)
        self.logger.info("\n\n Execute Operations, inputs: " + str( task.inputs ) + ", task metadata = " + str(task.metadata) + ", axes = " + str(axes) )
        for input in kernel_inputs:
            t0 = time.time()
            results.append( npArray.createResult( task, input,  input.array.sum( axis=axes,   keepdims=True ) ) )
            results.append( npArray.createAuxResult( task.rId + "_WEIGHTS_", input.origin, dict( input.metadata, **task.metadata ),  input.array.count(axis=self.getAxes(task.metadata), keepdims=True ) ) )
            t1 = time.time()
            self.logger.info( " ------------------------------- SUMW KERNEL: Operating on input '{0}', shape = {1}, origin = {2}, time = {3}".format( input.name, input.shape, input.origin, t1-t0 ))
        return results

class WeightedAverageKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("avew", "Weighted Average Kernel","Computes the weighted average of the array elements along the given axes.", reduceOp="avew", weights="cosine" ) )

    def executeOperations(self, task, inputs):
        available_inputIds = [ inputId.split('-')[0] for inputId in inputs ]
        data_inputIds = [ inputId.split('-')[0] for inputId in task.inputs ]
        weight_inputIds = [ ( inputId+"_WEIGHTS_" if (inputId+"_WEIGHTS_" in available_inputIds) else None ) for inputId in data_inputIds ]
        inputs_with_weights = zip( data_inputIds, weight_inputIds )
        results = []
        for input_pair in inputs_with_weights:
            input = inputs.get( input_pair[0] )  # npArray
            if( input == None ): raise Exception( "Can't find input " + input_pair[0] + " in numpyModule.WeightedAverageKernel")
            else :
                weights = inputs.get( input_pair[1] ).array if( input_pair[1] != None ) else None
                axes = self.getOrderedAxes(task,input)
                self.logger.info("\n Executing average, input: " + str( input_pair[0] ) + ", task metadata = " + str(task.metadata) + " Input metadata: " + str( input.metadata ) )
                t0 = time.time()
                result = input.array
                for axis in axes:
                    current_shape = list( result.shape )
                    ( result, weights ) = np.ma.average( result, axis, weights, True )
                    current_shape[axis] = 1
                    result = result.reshape( current_shape )
                    weights = weights.reshape( current_shape )

                results.append( npArray.createResult( task, input, result ) )
                t1 = time.time()
                self.logger.info( " ------------------------------- AVEW KERNEL: Operating on input '{0}', shape = {1}, origin = {2}, time = {3}".format( input.name, input.shape, input.origin, t1-t0 ))
        return results

    def getOrderedAxes(self,task,input):
        axes=list(self.getAxes(task.metadata))
        dimensions = input.metadata.get("dimensions","").split(',')
        axis_map = { dimensions[axis]: axis for axis in axes }
        y_axis = axis_map.get("lat",None)
        if( y_axis != None ):
            y_axis_index = axes.index( y_axis )
            if( y_axis_index > 0 ):
                axes[0], axes[y_axis_index] = axes[y_axis_index], axes[0]
        return axes

class PtpKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("ptp", "Peak to Peak","Computes the peak to peak (maximum - minimum) value the along given axes.", parallelize=False ) )
    def executeOperation( self, task, input ):
        return npArray.createResult( task, input, input.array.ptp( axis=self.getAxes(task.metadata), keepdims=True ) )

class VarKernel(Kernel):
    def __init__( self ):
        Kernel.__init__( self, KernelSpec("var", "Variance","Computes the variance of the array elements along the given axes.", parallelize=False ) )
    def executeOperation( self, task, input ):
        return npArray.createResult( task, input, input.array.var( axis=self.getAxes(task.metadata), keepdims=True ) )

