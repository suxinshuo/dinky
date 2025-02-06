/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.flink.table.planner.operations;

import static org.apache.flink.table.types.extraction.ExtractionUtils.isAssignable;
import static org.apache.flink.table.types.logical.LogicalTypeRoot.STRUCTURED_TYPE;

import org.dinky.context.CustomTableEnvironmentContext;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.api.internal.ResultProvider;
import org.apache.flink.table.api.internal.TableResultImpl;
import org.apache.flink.table.api.internal.TableResultInternal;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.conversion.DataStructureConverter;
import org.apache.flink.table.data.conversion.DataStructureConverters;
import org.apache.flink.table.data.conversion.RowRowConverter;
import org.apache.flink.table.operations.CallProcedureOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.OperationUtils;
import org.apache.flink.table.planner.codegen.CodeGeneratorContext;
import org.apache.flink.table.planner.functions.casting.RowDataToStringConverterImpl;
import org.apache.flink.table.procedure.DefaultProcedureContext;
import org.apache.flink.table.procedure.ProcedureContext;
import org.apache.flink.table.procedures.Procedure;
import org.apache.flink.table.procedures.ProcedureDefinition;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.extraction.ExtractionUtils;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;
import org.apache.flink.table.types.utils.DataTypeUtils;
import org.apache.flink.table.utils.print.RowDataToStringConverter;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wrapper for valid call procedure operation generated by Planner. */
public class PlannerCallProcedureOperation implements CallProcedureOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlannerCallProcedureOperation.class);

    private final ObjectIdentifier procedureIdentifier;
    private final Procedure procedure;

    /** The internal represent for input arguments. */
    private final Object[] internalInputArguments;

    private final DataType[] inputTypes;
    private final DataType outputType;

    public PlannerCallProcedureOperation(
            ObjectIdentifier procedureIdentifier,
            Procedure procedure,
            Object[] internalInputArguments,
            DataType[] inputTypes,
            DataType outputType) {
        this.procedureIdentifier = procedureIdentifier;
        this.procedure = procedure;
        this.internalInputArguments = internalInputArguments;
        this.inputTypes = inputTypes;
        this.outputType = outputType;
    }

    @Override
    public TableResultInternal execute(Context ctx) {
        TableConfig tableConfig = ctx.getTableConfig();
        ClassLoader userClassLoader = ctx.getResourceManager().getUserClassLoader();

        // get the class for the args
        Class<?>[] argumentClz = new Class[1 + inputTypes.length];
        argumentClz[0] = ProcedureContext.class;
        for (int i = 0; i < inputTypes.length; i++) {
            argumentClz[i + 1] = inputTypes[i].getConversionClass();
        }

        // get the value for the args
        Object[] argumentVal = getConvertedArgumentValues(tableConfig, userClassLoader);

        // call the procedure, get result
        Object procedureResult = callProcedure(procedure, argumentClz, argumentVal);

        return procedureResultToTableResult(procedureResult, tableConfig, userClassLoader);
    }

    private Object[] getConvertedArgumentValues(TableConfig tableConfig, ClassLoader userClassLoader) {
        // should be [ProcedureContext, arg1, arg2, ..]
        Object[] argumentVal = new Object[1 + internalInputArguments.length];
        argumentVal[0] = getProcedureContext(tableConfig);
        for (int i = 0; i < internalInputArguments.length; i++) {
            argumentVal[i + 1] = (internalInputArguments[i] != null)
                    ? toExternal(internalInputArguments[i], inputTypes[i], userClassLoader)
                    : null;
        }
        return argumentVal;
    }

    private ProcedureContext getProcedureContext(TableConfig tableConfig) {
        Configuration configuration = new Configuration((Configuration) tableConfig.getRootConfiguration());
        configuration.addAll(tableConfig.getConfiguration());
        StreamExecutionEnvironment env = CustomTableEnvironmentContext.get().getStreamExecutionEnvironment();
        return new DefaultProcedureContext(env);
    }

    /** Convert the value with internal representation to the value with external representation. */
    private Object toExternal(Object internalValue, DataType inputType, ClassLoader classLoader) {
        if (!(DataTypeUtils.isInternal(inputType))) {
            // if the expected input type of the procedure is not internal type,
            // which means the converted Flink internal value doesn't
            // match the expected input type, then we need to convert the Flink
            // internal value to external value
            DataStructureConverter<Object, Object> converter = DataStructureConverters.getConverter(inputType);
            converter.open(classLoader);
            return converter.toExternal(internalValue);
        } else {
            return internalValue;
        }
    }

    private Object callProcedure(Procedure procedure, Class<?>[] inputClz, Object[] inputArgs) {
        String callMethodName = ProcedureDefinition.PROCEDURE_CALL;
        // get the possible methods to invoke
        final List<Method> methods = ExtractionUtils.collectMethods(procedure.getClass(), callMethodName);
        List<Method> callMethods = methods.stream()
                .filter(method -> ExtractionUtils.isInvokable(method, inputClz)
                        && method.getReturnType().isArray()
                        && isAssignable(
                                outputType.getConversionClass(),
                                method.getReturnType().getComponentType(),
                                true))
                .collect(Collectors.toList());
        if (callMethods.isEmpty()) {
            throw new ValidationException(String.format(
                    "Could not find an implementation method '%s' in class '%s' for procedure '%s' that "
                            + "matches the following signature:\n%s",
                    callMethodName,
                    procedure.getClass().getName(),
                    procedureIdentifier,
                    ExtractionUtils.createMethodSignatureString(
                            callMethodName, inputClz, outputType.getConversionClass())));
        }
        if (callMethods.size() > 1) {
            LOGGER.warn(
                    "There are multiple methods matching the procedure calling: {}. "
                            + " Only invoke the first method: {}.",
                    methods,
                    methods.get(0));
        }
        return invokeCallMethod(procedure, callMethods.get(0), inputArgs);
    }

    private Object invokeCallMethod(Procedure procedure, Method calMethod, Object[] inputArgs) {
        try {
            if (calMethod.isVarArgs()) {
                // if the method is var args, we need to adjust the inputArgs to make
                // it match the signature, the logic of which is as follows:
                // assuming the method is varMethod(arg1, arg2, ...)
                // the args to involve this method is arg1, arg2, arg3, arg4
                // first, we get the index of the vararg, which is 2 in this case
                // then, we compose the args after the index to a array, [arg3, arg4],
                // finally, we get the right argument to call this var-args method,
                // that's arg1, arg2, [arg3, arg4]

                final int paramCount = calMethod.getParameterCount();
                final int varargsIndex = paramCount - 1;
                Object[] newInputArgs = new Object[paramCount];
                System.arraycopy(inputArgs, 0, newInputArgs, 0, varargsIndex);
                // handle the remaining values in the input args
                // get the class type for the varargs
                Class<?> varargsElementType = calMethod.getParameterTypes()[varargsIndex].getComponentType();
                int varargsLength = inputArgs.length - varargsIndex;
                Object varargs = Array.newInstance(varargsElementType, varargsLength);
                System.arraycopy(inputArgs, varargsIndex, varargs, 0, varargsLength);
                newInputArgs[varargsIndex] = varargs;
                inputArgs = newInputArgs;
            }
            LOGGER.info("Invoke method {} with arguments: {}.", calMethod, inputArgs);
            return calMethod.invoke(procedure, inputArgs);
        } catch (IllegalAccessException e) {
            throw new TableException(
                    String.format(
                            "Access to the method %s was denied: %s.",
                            ProcedureDefinition.PROCEDURE_CALL, e.getMessage()),
                    e);
        } catch (InvocationTargetException e) {
            Throwable exceptionInMethod = e.getTargetException();
            throw new TableException(
                    String.format(
                            "The %s method caused an error: %s.",
                            ProcedureDefinition.PROCEDURE_CALL, exceptionInMethod.getMessage()),
                    e);
        } catch (Throwable t) {
            throw new TableException(
                    String.format(
                            "An error occurred while invoking the procedure's %s method: %s.",
                            ProcedureDefinition.PROCEDURE_CALL, t.getMessage()),
                    t);
        }
    }

    /** Convert the result of procedure to table result . */
    private TableResultInternal procedureResultToTableResult(
            Object procedureResult, TableConfig tableConfig, ClassLoader userClassLoader) {
        // get result converter
        ZoneId zoneId = tableConfig.getLocalTimeZone();
        DataType tableResultType = outputType;
        // if is not composite type, wrap it to composited type
        if (!LogicalTypeChecks.isCompositeType(outputType.getLogicalType())) {
            tableResultType = DataTypes.ROW(DataTypes.FIELD("result", tableResultType));
        }

        RowRowConverter rowConverter = null;
        // if the output is struct type,
        // we need a row converter to help convert it to Row.
        // we will first convert the struct value to RowData, and then use the row converter
        // to convert the RowData to Row.
        if (outputType.getLogicalType().getTypeRoot() == STRUCTURED_TYPE) {
            rowConverter = RowRowConverter.create(tableResultType);
            rowConverter.open(userClassLoader);
        }

        // expand the result type to schema
        ResolvedSchema resultSchema = DataTypeUtils.expandCompositeTypeToSchema(tableResultType);
        RowDataToStringConverter rowDataToStringConverter = new RowDataToStringConverterImpl(
                tableResultType,
                zoneId,
                userClassLoader,
                tableConfig
                        .get(ExecutionConfigOptions.TABLE_EXEC_LEGACY_CAST_BEHAVIOUR)
                        .isEnabled(),
                new CodeGeneratorContext(tableConfig, userClassLoader));
        // create DataStructure converters
        DataStructureConverter<Object, Object> converter = DataStructureConverters.getConverter(outputType);
        converter.open(userClassLoader);

        return TableResultImpl.builder()
                .resultProvider(new CallProcedureResultProvider(
                        converter, rowDataToStringConverter, rowConverter, procedureResult))
                .schema(resultSchema)
                .resultKind(ResultKind.SUCCESS_WITH_CONTENT)
                .build();
    }

    @Override
    public String asSummaryString() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("procedureIdentifier", procedureIdentifier);
        params.put("inputTypes", inputTypes);
        params.put("outputTypes", outputType);
        params.put("arguments", internalInputArguments);
        return OperationUtils.formatWithChildren(
                "CALL PROCEDURE", params, Collections.emptyList(), Operation::asSummaryString);
    }

    /** A result provider for the result of calling procedure. */
    static final class CallProcedureResultProvider implements ResultProvider {

        private final DataStructureConverter<Object, Object> converter;
        private final RowDataToStringConverter toStringConverter;

        // a converter to convert internal RowData to Row
        private final @Nullable RowRowConverter rowConverter;
        private final Object[] result;

        public CallProcedureResultProvider(
                DataStructureConverter<Object, Object> converter,
                RowDataToStringConverter toStringConverter,
                @Nullable RowRowConverter rowConverter,
                Object result) {
            this.converter = converter;
            this.toStringConverter = toStringConverter;
            this.result = toResultArray(result);
            this.rowConverter = rowConverter;
        }

        @Override
        public ResultProvider setJobClient(JobClient jobClient) {
            return this;
        }

        @Override
        public CloseableIterator<RowData> toInternalIterator() {
            Iterator<Object> objectIterator = Arrays.stream(result).iterator();

            return new CloseableIterator<RowData>() {
                @Override
                public boolean hasNext() {
                    return objectIterator.hasNext();
                }

                @Override
                public RowData next() {
                    return toRowData(objectIterator.next());
                }

                @Override
                public void close() {}
            };
        }

        private RowData toRowData(Object externalValue) {
            Object element = converter.toInternalOrNull(externalValue);
            if (!(element instanceof RowData)) {
                return GenericRowData.of(element);
            }
            return (RowData) element;
        }

        @Override
        public CloseableIterator<Row> toExternalIterator() {
            Iterator<Object> objectIterator = Arrays.stream(result).iterator();

            return new CloseableIterator<Row>() {
                @Override
                public boolean hasNext() {
                    return objectIterator.hasNext();
                }

                @Override
                public Row next() {
                    Object element = objectIterator.next();
                    if (!(element instanceof Row)) {
                        if (rowConverter != null) {
                            // first convert the extern value to internal RowData,
                            // then convert the RowData to Row
                            return rowConverter.toExternal(toRowData(element));
                        } else {
                            return Row.of(element);
                        }
                    }
                    return (Row) element;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public RowDataToStringConverter getRowDataStringConverter() {
            return toStringConverter;
        }

        @Override
        public boolean isFirstRowReady() {
            // always return true
            return true;
        }

        private Object[] toResultArray(Object result) {
            // the result may be primitive array,
            // convert it to primitive wrapper array
            if (isPrimitiveArray(result)) {
                return toPrimitiveWrapperArray(result);
            }
            return (Object[]) result;
        }

        private boolean isPrimitiveArray(Object result) {
            return result.getClass().isArray()
                    && result.getClass().getComponentType().isPrimitive();
        }

        private Object[] toPrimitiveWrapperArray(Object primitiveArray) {
            int length = Array.getLength(primitiveArray);
            Object[] objArray = new Object[length];

            for (int i = 0; i < length; i++) {
                objArray[i] = Array.get(primitiveArray, i);
            }
            return objArray;
        }
    }
}
