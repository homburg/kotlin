/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiElement;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.backend.common.bridges.Bridge;
import org.jetbrains.kotlin.backend.common.bridges.ImplKt;
import org.jetbrains.kotlin.codegen.annotation.AnnotatedWithOnlyTargetedAnnotations;
import org.jetbrains.kotlin.codegen.context.*;
import org.jetbrains.kotlin.codegen.intrinsics.TypeIntrinsics;
import org.jetbrains.kotlin.codegen.optimization.OptimizationMethodVisitor;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.codegen.state.JetTypeMapper;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.annotations.Annotated;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget;
import org.jetbrains.kotlin.jvm.RuntimeAssertionInfo;
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature;
import org.jetbrains.kotlin.load.java.JvmAbi;
import org.jetbrains.kotlin.load.java.JvmAnnotationNames;
import org.jetbrains.kotlin.load.java.SpecialBuiltinMembers;
import org.jetbrains.kotlin.load.kotlin.nativeDeclarations.NativeKt;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.annotations.AnnotationUtilKt;
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.CallResolverUtilKt;
import org.jetbrains.kotlin.resolve.constants.ArrayValue;
import org.jetbrains.kotlin.resolve.constants.ConstantValue;
import org.jetbrains.kotlin.resolve.constants.KClassValue;
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin;
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOriginKt;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodParameterKind;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodParameterSignature;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.org.objectweb.asm.AnnotationVisitor;
import org.jetbrains.org.objectweb.asm.Label;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter;
import org.jetbrains.org.objectweb.asm.commons.Method;
import org.jetbrains.org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.jetbrains.kotlin.builtins.KotlinBuiltIns.isNullableAny;
import static org.jetbrains.kotlin.codegen.AsmUtil.*;
import static org.jetbrains.kotlin.codegen.serialization.JvmSerializationBindings.*;
import static org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.DECLARATION;
import static org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.*;
import static org.jetbrains.kotlin.resolve.DescriptorToSourceUtils.getSourceFromDescriptor;
import static org.jetbrains.kotlin.resolve.DescriptorUtils.*;
import static org.jetbrains.kotlin.resolve.jvm.AsmTypes.OBJECT_TYPE;
import static org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.*;
import static org.jetbrains.org.objectweb.asm.Opcodes.*;

public class FunctionCodegen {
    public final GenerationState state;
    private final JetTypeMapper typeMapper;
    private final BindingContext bindingContext;
    private final CodegenContext owner;
    private final ClassBuilder v;
    private final MemberCodegen<?> memberCodegen;

    public FunctionCodegen(
            @NotNull CodegenContext owner,
            @NotNull ClassBuilder v,
            @NotNull GenerationState state,
            @NotNull MemberCodegen<?> memberCodegen
    ) {
        this.owner = owner;
        this.v = v;
        this.state = state;
        this.typeMapper = state.getTypeMapper();
        this.bindingContext = state.getBindingContext();
        this.memberCodegen = memberCodegen;
    }

    public void gen(@NotNull KtNamedFunction function) {
        SimpleFunctionDescriptor functionDescriptor = bindingContext.get(BindingContext.FUNCTION, function);
        assert functionDescriptor != null : "No descriptor for function " + function.getText() + "\n" +
                                            "in " + function.getContainingFile().getVirtualFile();

        if (owner.getContextKind() != OwnerKind.DEFAULT_IMPLS || function.hasBody()) {
            generateMethod(JvmDeclarationOriginKt.OtherOrigin(function, functionDescriptor), functionDescriptor,
                           new FunctionGenerationStrategy.FunctionDefault(state, functionDescriptor, function));
        }

        generateDefaultIfNeeded(owner.intoFunction(functionDescriptor), functionDescriptor, owner.getContextKind(),
                                DefaultParameterValueLoader.DEFAULT, function);

        generateOverloadsWithDefaultValues(function, functionDescriptor, functionDescriptor);
    }

    public void generateOverloadsWithDefaultValues(
            @Nullable KtNamedFunction function,
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull FunctionDescriptor delegateFunctionDescriptor
    ) {
        new DefaultParameterValueSubstitutor(state).generateOverloadsIfNeeded(
                function, functionDescriptor, delegateFunctionDescriptor, owner.getContextKind(), v
        );
    }

    public void generateMethod(
            @NotNull JvmDeclarationOrigin origin,
            @NotNull FunctionDescriptor descriptor,
            @NotNull FunctionGenerationStrategy strategy
    ) {
        generateMethod(origin, descriptor, owner.intoFunction(descriptor), strategy);
    }

    public void generateMethod(
            @NotNull JvmDeclarationOrigin origin,
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull MethodContext methodContext,
            @NotNull FunctionGenerationStrategy strategy
    ) {
        OwnerKind contextKind = methodContext.getContextKind();
        if (isInterface(functionDescriptor.getContainingDeclaration()) &&
            functionDescriptor.getVisibility() == Visibilities.PRIVATE &&
            contextKind != OwnerKind.DEFAULT_IMPLS) {
            return;
        }

        JvmMethodSignature jvmSignature = typeMapper.mapSignature(functionDescriptor, contextKind);
        Method asmMethod = jvmSignature.getAsmMethod();

        int flags = getMethodAsmFlags(functionDescriptor, contextKind);
        boolean isNative = NativeKt.hasNativeAnnotation(functionDescriptor);

        if (isNative && owner instanceof DelegatingFacadeContext) {
            // Native methods are only defined in facades and do not need package part implementations
            return;
        }
        MethodVisitor mv = v.newMethod(origin,
                                       flags,
                                       asmMethod.getName(),
                                       asmMethod.getDescriptor(),
                                       jvmSignature.getGenericsSignature(),
                                       getThrownExceptions(functionDescriptor, typeMapper));

        String implClassName = CodegenContextUtil.getImplementationClassShortName(owner);
        if (implClassName != null) {
            v.getSerializationBindings().put(IMPL_CLASS_NAME_FOR_CALLABLE, functionDescriptor, implClassName);
        }
        if (CodegenContextUtil.isImplClassOwner(owner)) {
            v.getSerializationBindings().put(METHOD_FOR_FUNCTION, functionDescriptor, asmMethod);
        }

        generateMethodAnnotations(functionDescriptor, asmMethod, mv);

        generateParameterAnnotations(functionDescriptor, mv, typeMapper.mapSignature(functionDescriptor));

        generateBridges(functionDescriptor);

        boolean staticInCompanionObject = AnnotationUtilKt.isPlatformStaticInCompanionObject(functionDescriptor);
        if (staticInCompanionObject) {
            ImplementationBodyCodegen parentBodyCodegen = (ImplementationBodyCodegen) memberCodegen.getParentCodegen();
            parentBodyCodegen.addAdditionalTask(new PlatformStaticGenerator(functionDescriptor, origin, state));
        }

        if (state.getClassBuilderMode() == ClassBuilderMode.LIGHT_CLASSES || isAbstractMethod(functionDescriptor, contextKind)) {
            generateLocalVariableTable(
                    mv,
                    jvmSignature,
                    functionDescriptor,
                    getThisTypeForFunction(functionDescriptor, methodContext, typeMapper),
                    new Label(),
                    new Label(),
                    contextKind
            );

            mv.visitEnd();
            return;
        }

        if (!isNative) {
            generateMethodBody(mv, functionDescriptor, methodContext, jvmSignature, strategy, memberCodegen);
        }
        else if (staticInCompanionObject) {
            // native platformStatic foo() in companion object should delegate to the static native function moved to the outer class
            mv.visitCode();
            FunctionDescriptor staticFunctionDescriptor = PlatformStaticGenerator.createStaticFunctionDescriptor(functionDescriptor);
            JvmMethodSignature jvmMethodSignature =
                    typeMapper.mapSignature(memberCodegen.getContext().accessibleDescriptor(staticFunctionDescriptor, null));
            Type owningType = typeMapper.mapClass((ClassifierDescriptor) staticFunctionDescriptor.getContainingDeclaration());
            generateDelegateToMethodBody(false, mv, jvmMethodSignature.getAsmMethod(), owningType.getInternalName());
        }

        endVisit(mv, null, origin.getElement());

        methodContext.recordSyntheticAccessorIfNeeded(functionDescriptor, bindingContext);
    }

    private void generateMethodAnnotations(
            @NotNull FunctionDescriptor functionDescriptor,
            Method asmMethod,
            MethodVisitor mv
    ) {
        AnnotationCodegen annotationCodegen = AnnotationCodegen.forMethod(mv, typeMapper);

        if (functionDescriptor instanceof PropertyAccessorDescriptor) {
            AnnotationUseSiteTarget target = functionDescriptor instanceof PropertySetterDescriptor ? PROPERTY_SETTER : PROPERTY_GETTER;
            annotationCodegen.genAnnotations(functionDescriptor, asmMethod.getReturnType(), target);
        }
        else {
            annotationCodegen.genAnnotations(functionDescriptor, asmMethod.getReturnType());
        }

        writePackageFacadeMethodAnnotationsIfNeeded(mv);
    }

    private void writePackageFacadeMethodAnnotationsIfNeeded(MethodVisitor mv) {
        if (owner instanceof PackageFacadeContext) {
            PackageFacadeContext packageFacadeContext = (PackageFacadeContext) owner;
            Type delegateToClassType = packageFacadeContext.getPublicFacadeType();
            if (delegateToClassType != null) {
                String className = delegateToClassType.getClassName();
                AnnotationVisitor
                        av = mv.visitAnnotation(AsmUtil.asmDescByFqNameWithoutInnerClasses(JvmAnnotationNames.KOTLIN_DELEGATED_METHOD), true);
                av.visit(JvmAnnotationNames.IMPLEMENTATION_CLASS_NAME_FIELD_NAME, className);
                av.visitEnd();
            }
        }
    }

    private void generateParameterAnnotations(
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull MethodVisitor mv,
            @NotNull JvmMethodSignature jvmSignature
    ) {
        Iterator<ValueParameterDescriptor> iterator = functionDescriptor.getValueParameters().iterator();
        List<JvmMethodParameterSignature> kotlinParameterTypes = jvmSignature.getValueParameters();

        for (int i = 0; i < kotlinParameterTypes.size(); i++) {
            JvmMethodParameterSignature parameterSignature = kotlinParameterTypes.get(i);
            JvmMethodParameterKind kind = parameterSignature.getKind();
            if (kind.isSkippedInGenericSignature()) {
                markEnumOrInnerConstructorParameterAsSynthetic(mv, i);
                continue;
            }

            if (kind == JvmMethodParameterKind.VALUE) {
                ValueParameterDescriptor parameter = iterator.next();
                if (parameter.getIndex() != i) {
                    v.getSerializationBindings().put(INDEX_FOR_VALUE_PARAMETER, parameter, i);
                }
                AnnotationCodegen annotationCodegen = AnnotationCodegen.forParameter(i, mv, typeMapper);

                if (functionDescriptor instanceof PropertySetterDescriptor) {
                    PropertyDescriptor propertyDescriptor = ((PropertySetterDescriptor) functionDescriptor).getCorrespondingProperty();
                    Annotated targetedAnnotations = new AnnotatedWithOnlyTargetedAnnotations(propertyDescriptor);
                    annotationCodegen.genAnnotations(targetedAnnotations, parameterSignature.getAsmType(), SETTER_PARAMETER);
                }

                if (functionDescriptor instanceof ConstructorDescriptor) {
                    annotationCodegen.genAnnotations(parameter, parameterSignature.getAsmType(), CONSTRUCTOR_PARAMETER);
                }
                else {
                    annotationCodegen.genAnnotations(parameter, parameterSignature.getAsmType());
                }
            }
            else if (kind == JvmMethodParameterKind.RECEIVER) {
                ReceiverParameterDescriptor receiver = ((functionDescriptor instanceof PropertyAccessorDescriptor)
                                             ? ((PropertyAccessorDescriptor) functionDescriptor).getCorrespondingProperty()
                                             : functionDescriptor).getExtensionReceiverParameter();
                if (receiver != null) {
                    AnnotationCodegen annotationCodegen = AnnotationCodegen.forParameter(i, mv, typeMapper);
                    Annotated targetedAnnotations = new AnnotatedWithOnlyTargetedAnnotations(receiver.getType());
                    annotationCodegen.genAnnotations(targetedAnnotations, parameterSignature.getAsmType(), RECEIVER);
                }
            }
        }
    }

    private void markEnumOrInnerConstructorParameterAsSynthetic(MethodVisitor mv, int i) {
        // IDEA's ClsPsi builder fails to annotate synthetic parameters
        if (state.getClassBuilderMode() == ClassBuilderMode.LIGHT_CLASSES) return;

        // This is needed to avoid RuntimeInvisibleParameterAnnotations error in javac:
        // see MethodWriter.visitParameterAnnotation()

        AnnotationVisitor av = mv.visitParameterAnnotation(i, "Ljava/lang/Synthetic;", true);
        if (av != null) {
            av.visitEnd();
        }
    }

    @Nullable
    private static Type getThisTypeForFunction(
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull MethodContext context,
            @NotNull JetTypeMapper typeMapper
    ) {
        ReceiverParameterDescriptor dispatchReceiver = functionDescriptor.getDispatchReceiverParameter();
        if (functionDescriptor instanceof ConstructorDescriptor) {
            return typeMapper.mapType(functionDescriptor);
        }
        else if (dispatchReceiver != null) {
            return typeMapper.mapType(dispatchReceiver.getType());
        }
        else if (isFunctionLiteral(functionDescriptor) ||
                 isLocalFunction(functionDescriptor) ||
                 isFunctionExpression(functionDescriptor)) {
            return typeMapper.mapType(context.getThisDescriptor());
        }
        else {
            return null;
        }
    }

    public static void generateMethodBody(
            @NotNull MethodVisitor mv,
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull MethodContext context,
            @NotNull JvmMethodSignature signature,
            @NotNull FunctionGenerationStrategy strategy,
            @NotNull MemberCodegen<?> parentCodegen
    ) {
        mv.visitCode();

        Label methodBegin = new Label();
        mv.visitLabel(methodBegin);

        JetTypeMapper typeMapper = parentCodegen.typeMapper;

        Label methodEnd;

        int functionFakeIndex = -1;
        int lambdaFakeIndex = -1;

        if (context.getParentContext() instanceof DelegatingFacadeContext) {
            generateFacadeDelegateMethodBody(mv, signature.getAsmMethod(), (DelegatingFacadeContext) context.getParentContext());
            methodEnd = new Label();
        }
        else {
            FrameMap frameMap = createFrameMap(parentCodegen.state, functionDescriptor, signature, isStaticMethod(context.getContextKind(),
                                                                                                                  functionDescriptor));
            if (context.isInlineFunction()) {
                functionFakeIndex = frameMap.enterTemp(Type.INT_TYPE);
            }

            if (context.isInliningLambda()) {
                lambdaFakeIndex = frameMap.enterTemp(Type.INT_TYPE);
            }

            Label methodEntry = new Label();
            mv.visitLabel(methodEntry);
            context.setMethodStartLabel(methodEntry);

            if (!JetTypeMapper.isAccessor(functionDescriptor)) {
                genNotNullAssertionsForParameters(new InstructionAdapter(mv), parentCodegen.state, functionDescriptor, frameMap);
            }
            methodEnd = new Label();
            context.setMethodEndLabel(methodEnd);
            strategy.generateBody(mv, frameMap, signature, context, parentCodegen);
        }

        mv.visitLabel(methodEnd);

        Type thisType = getThisTypeForFunction(functionDescriptor, context, typeMapper);
        generateLocalVariableTable(mv, signature, functionDescriptor, thisType, methodBegin, methodEnd, context.getContextKind());

        if (context.isInlineFunction() && functionFakeIndex != -1) {
            mv.visitLocalVariable(
                    JvmAbi.LOCAL_VARIABLE_NAME_PREFIX_INLINE_FUNCTION + functionDescriptor.getName().asString(),
                    Type.INT_TYPE.getDescriptor(), null,
                    methodBegin, methodEnd,
                    functionFakeIndex);
        }

        if (context.isInliningLambda() && thisType != null && lambdaFakeIndex != -1) {
            String name = thisType.getClassName();
            int indexOfLambdaOrdinal = name.lastIndexOf("$");
            if (indexOfLambdaOrdinal > 0) {
                int lambdaOrdinal = Integer.parseInt(name.substring(indexOfLambdaOrdinal + 1));
                mv.visitLocalVariable(
                        JvmAbi.LOCAL_VARIABLE_NAME_PREFIX_INLINE_ARGUMENT + lambdaOrdinal,
                        Type.INT_TYPE.getDescriptor(), null,
                        methodBegin, methodEnd,
                        lambdaFakeIndex);
            }
        }
    }

    private static void generateLocalVariableTable(
            @NotNull MethodVisitor mv,
            @NotNull JvmMethodSignature jvmMethodSignature,
            @NotNull FunctionDescriptor functionDescriptor,
            @Nullable Type thisType,
            @NotNull Label methodBegin,
            @NotNull Label methodEnd,
            @NotNull OwnerKind ownerKind
    ) {
        Iterator<ValueParameterDescriptor> valueParameters = functionDescriptor.getValueParameters().iterator();
        List<JvmMethodParameterSignature> params = jvmMethodSignature.getValueParameters();
        int shift = 0;

        boolean isStatic = AsmUtil.isStaticMethod(ownerKind, functionDescriptor);
        if (!isStatic) {
            //add this
            if (thisType != null) {
                mv.visitLocalVariable("this", thisType.getDescriptor(), null, methodBegin, methodEnd, shift);
            }
            else {
                //TODO: provide thisType for callable reference
            }
            shift++;
        }

        for (int i = 0; i < params.size(); i++) {
            JvmMethodParameterSignature param = params.get(i);
            JvmMethodParameterKind kind = param.getKind();
            String parameterName;

            if (kind == JvmMethodParameterKind.VALUE) {
                ValueParameterDescriptor parameter = valueParameters.next();
                parameterName = parameter.getName().asString();
            }
            else {
                String lowercaseKind = kind.name().toLowerCase();
                parameterName = needIndexForVar(kind)
                                ? "$" + lowercaseKind + "$" + i
                                : "$" + lowercaseKind;
            }

            Type type = param.getAsmType();
            mv.visitLocalVariable(parameterName, type.getDescriptor(), null, methodBegin, methodEnd, shift);
            shift += type.getSize();
        }
    }

    private static void generateFacadeDelegateMethodBody(
            @NotNull MethodVisitor mv,
            @NotNull Method asmMethod,
            @NotNull DelegatingFacadeContext context
    ) {
        generateDelegateToMethodBody(true, mv, asmMethod, context.getDelegateToClassType().getInternalName());
    }

    private static void generateDelegateToMethodBody(
            boolean isStatic,
            @NotNull MethodVisitor mv,
            @NotNull Method asmMethod,
            @NotNull String classToDelegateTo
    ) {
        InstructionAdapter iv = new InstructionAdapter(mv);
        Type[] argTypes = asmMethod.getArgumentTypes();

        // The first line of some package file is written to the line number attribute of a static delegate to allow to 'step into' it
        // This is similar to what javac does with bridge methods
        Label label = new Label();
        iv.visitLabel(label);
        iv.visitLineNumber(1, label);

        int k = isStatic ? 0 : 1;
        for (Type argType : argTypes) {
            iv.load(k, argType);
            k += argType.getSize();
        }
        iv.invokestatic(classToDelegateTo, asmMethod.getName(), asmMethod.getDescriptor(), false);
        iv.areturn(asmMethod.getReturnType());
    }

    private static boolean needIndexForVar(JvmMethodParameterKind kind) {
        return kind == JvmMethodParameterKind.CAPTURED_LOCAL_VARIABLE ||
               kind == JvmMethodParameterKind.ENUM_NAME_OR_ORDINAL ||
               kind == JvmMethodParameterKind.SUPER_CALL_PARAM;
    }

    public static void endVisit(MethodVisitor mv, @Nullable String description, @Nullable PsiElement method) {
        try {
            mv.visitMaxs(-1, -1);
            mv.visitEnd();
        }
        catch (ProcessCanceledException e) {
            throw e;
        }
        catch (Throwable t) {
            String bytecode = renderByteCodeIfAvailable(mv);
            throw new CompilationException(
                    "wrong code generated" +
                    (description != null ? " for " + description : "") +
                    t.getClass().getName() +
                    " " +
                    t.getMessage() +
                    (bytecode != null ? "\nbytecode:\n" + bytecode : ""),
                    t, method);
        }
    }

    private static String renderByteCodeIfAvailable(MethodVisitor mv) {
        String bytecode = null;

        if (mv instanceof OptimizationMethodVisitor) {
            mv = ((OptimizationMethodVisitor) mv).getTraceMethodVisitorIfPossible();
        }

        if (mv instanceof TraceMethodVisitor) {
            TraceMethodVisitor traceMethodVisitor = (TraceMethodVisitor) mv;
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            traceMethodVisitor.p.print(pw);
            pw.close();
            bytecode = sw.toString();
        }
        return bytecode;
    }

    public void generateBridges(@NotNull FunctionDescriptor descriptor) {
        if (descriptor instanceof ConstructorDescriptor) return;
        if (owner.getContextKind() == OwnerKind.DEFAULT_IMPLS) return;
        if (isInterface(descriptor.getContainingDeclaration())) return;

        // equals(Any?), hashCode(), toString() never need bridges
        if (isMethodOfAny(descriptor)) return;

        // If the function doesn't have a physical declaration among super-functions, it's a SAM adapter or alike and doesn't need bridges
        if (CallResolverUtilKt.isOrOverridesSynthesized(descriptor)) return;

        boolean isSpecial = SpecialBuiltinMembers.getOverriddenBuiltinWithDifferentJvmDescriptor(descriptor) != null;

        Set<Bridge<Method>> bridgesToGenerate;
        if (!isSpecial) {
            bridgesToGenerate = ImplKt.generateBridgesForFunctionDescriptor(
                    descriptor,
                    new Function1<FunctionDescriptor, Method>() {
                        @Override
                        public Method invoke(FunctionDescriptor descriptor) {
                            return typeMapper.mapSignature(descriptor).getAsmMethod();
                        }
                    }
            );
            if (!bridgesToGenerate.isEmpty()) {
                PsiElement origin = descriptor.getKind() == DECLARATION ? getSourceFromDescriptor(descriptor) : null;
                boolean isSpecialBridge =
                        BuiltinMethodsWithSpecialGenericSignature.getOverriddenBuiltinFunctionWithErasedValueParametersInJava(descriptor) != null;

                for (Bridge<Method> bridge : bridgesToGenerate) {
                    generateBridge(origin, descriptor, bridge.getFrom(), bridge.getTo(), isSpecialBridge, false);
                }
            }
        }
        else {
            Set<BridgeForBuiltinSpecial<Method>> specials = BuiltinSpecialBridgesUtil.generateBridgesForBuiltinSpecial(
                    descriptor,
                    new Function1<FunctionDescriptor, Method>() {
                        @Override
                        public Method invoke(FunctionDescriptor descriptor) {
                            return typeMapper.mapSignature(descriptor).getAsmMethod();
                        }
                    }
            );

            if (!specials.isEmpty()) {
                PsiElement origin = descriptor.getKind() == DECLARATION ? getSourceFromDescriptor(descriptor) : null;
                for (BridgeForBuiltinSpecial<Method> bridge : specials) {
                    generateBridge(
                            origin, descriptor, bridge.getFrom(), bridge.getTo(),
                            bridge.isSpecial(), bridge.isDelegateToSuper());
                }
            }

            if (!descriptor.getKind().isReal() && isAbstractMethod(descriptor, OwnerKind.IMPLEMENTATION)) {
                CallableDescriptor overridden = SpecialBuiltinMembers.getOverriddenBuiltinWithDifferentJvmDescriptor(descriptor);
                assert overridden != null;

                Method method = typeMapper.mapSignature(descriptor).getAsmMethod();
                int flags = ACC_ABSTRACT | getVisibilityAccessFlag(descriptor);
                v.newMethod(JvmDeclarationOriginKt.OtherOrigin(overridden), flags, method.getName(), method.getDescriptor(), null, null);
            }
        }
    }

    private static boolean isMethodOfAny(@NotNull FunctionDescriptor descriptor) {
        String name = descriptor.getName().asString();
        List<ValueParameterDescriptor> parameters = descriptor.getValueParameters();
        if (parameters.isEmpty()) {
            return name.equals("hashCode") || name.equals("toString");
        }
        else if (parameters.size() == 1 && name.equals("equals")) {
            return isNullableAny(parameters.get(0).getType());
        }
        return false;
    }

    @NotNull
    public static String[] getThrownExceptions(@NotNull FunctionDescriptor function, @NotNull final JetTypeMapper mapper) {
        AnnotationDescriptor annotation = function.getAnnotations().findAnnotation(new FqName("kotlin.throws"));
        if (annotation == null) {
            annotation = function.getAnnotations().findAnnotation(new FqName("kotlin.jvm.Throws"));
        }

        if (annotation == null) return ArrayUtil.EMPTY_STRING_ARRAY;

        Collection<ConstantValue<?>> values = annotation.getAllValueArguments().values();
        if (values.isEmpty()) return ArrayUtil.EMPTY_STRING_ARRAY;

        Object value = values.iterator().next();
        if (!(value instanceof ArrayValue)) return ArrayUtil.EMPTY_STRING_ARRAY;
        ArrayValue arrayValue = (ArrayValue) value;

        List<String> strings = ContainerUtil.mapNotNull(
                arrayValue.getValue(),
                new Function<ConstantValue<?>, String>() {
                    @Override
                    public String fun(ConstantValue<?> constant) {
                        if (constant instanceof KClassValue) {
                            KClassValue classValue = (KClassValue) constant;
                            ClassDescriptor classDescriptor = DescriptorUtils.getClassDescriptorForType(classValue.getValue());
                            return mapper.mapClass(classDescriptor).getInternalName();
                        }
                        return null;
                    }
                }
        );
        return ArrayUtil.toStringArray(strings);
    }

    void generateDefaultIfNeeded(
            @NotNull MethodContext owner,
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull OwnerKind kind,
            @NotNull DefaultParameterValueLoader loadStrategy,
            @Nullable KtNamedFunction function
    ) {
        DeclarationDescriptor contextClass = owner.getContextDescriptor().getContainingDeclaration();

        if (kind != OwnerKind.DEFAULT_IMPLS && isInterface(contextClass)) {
            return;
        }

        if (!isDefaultNeeded(functionDescriptor)) {
            return;
        }

        int flags = getVisibilityAccessFlag(functionDescriptor) |
                    getDeprecatedAccessFlag(functionDescriptor) |
                    ACC_SYNTHETIC;
        if (!(functionDescriptor instanceof ConstructorDescriptor)) {
            flags |= ACC_STATIC;
        }
        // $default methods are never private to be accessible from other class files (e.g. inner) without the need of synthetic accessors
        flags &= ~ACC_PRIVATE;

        Method defaultMethod = typeMapper.mapDefaultMethod(functionDescriptor, kind);

        MethodVisitor mv = v.newMethod(
                JvmDeclarationOriginKt.Synthetic(function, functionDescriptor),
                flags,
                defaultMethod.getName(),
                defaultMethod.getDescriptor(), null,
                getThrownExceptions(functionDescriptor, typeMapper)
        );

        // Only method annotations are copied to the $default method. Parameter annotations are not copied until there are valid use cases;
        // enum constructors have two additional synthetic parameters which somewhat complicate this task
        AnnotationCodegen.forMethod(mv, typeMapper).genAnnotations(functionDescriptor, defaultMethod.getReturnType());

        if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
            if (this.owner instanceof DelegatingFacadeContext) {
                mv.visitCode();
                generateFacadeDelegateMethodBody(mv, defaultMethod, (DelegatingFacadeContext) this.owner);
                endVisit(mv, "default method delegation", getSourceFromDescriptor(functionDescriptor));
            }
            else {
                mv.visitCode();
                generateDefaultImplBody(owner, functionDescriptor, mv, loadStrategy, function, memberCodegen);
                endVisit(mv, "default method", getSourceFromDescriptor(functionDescriptor));
            }
        }
    }

    public static void generateDefaultImplBody(
            @NotNull MethodContext methodContext,
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull MethodVisitor mv,
            @NotNull DefaultParameterValueLoader loadStrategy,
            @Nullable KtNamedFunction function,
            @NotNull MemberCodegen<?> parentCodegen
    ) {
        GenerationState state = parentCodegen.state;
        JvmMethodSignature signature = state.getTypeMapper().mapSignature(functionDescriptor, methodContext.getContextKind());

        boolean isStatic = isStaticMethod(methodContext.getContextKind(), functionDescriptor);
        FrameMap frameMap = createFrameMap(state, functionDescriptor, signature, isStatic);

        ExpressionCodegen codegen = new ExpressionCodegen(mv, frameMap, signature.getReturnType(), methodContext, state, parentCodegen);

        CallGenerator generator = codegen.getOrCreateCallGenerator(functionDescriptor, function);

        loadExplicitArgumentsOnStack(OBJECT_TYPE, isStatic, signature, generator);

        List<JvmMethodParameterSignature> mappedParameters = signature.getValueParameters();
        int capturedArgumentsCount = 0;
        while (capturedArgumentsCount < mappedParameters.size() &&
               mappedParameters.get(capturedArgumentsCount).getKind() != JvmMethodParameterKind.VALUE) {
            capturedArgumentsCount++;
        }

        InstructionAdapter iv = new InstructionAdapter(mv);

        int maskIndex = 0;
        List<ValueParameterDescriptor> valueParameters = functionDescriptor.getValueParameters();
        for (int index = 0; index < valueParameters.size(); index++) {
            if (index % Integer.SIZE == 0) {
                maskIndex = frameMap.enterTemp(Type.INT_TYPE);
            }
            ValueParameterDescriptor parameterDescriptor = valueParameters.get(index);
            Type type = mappedParameters.get(capturedArgumentsCount + index).getAsmType();

            int parameterIndex = frameMap.getIndex(parameterDescriptor);
            if (parameterDescriptor.declaresDefaultValue()) {
                iv.load(maskIndex, Type.INT_TYPE);
                iv.iconst(1 << (index % Integer.SIZE));
                iv.and(Type.INT_TYPE);
                Label loadArg = new Label();
                iv.ifeq(loadArg);

                StackValue.local(parameterIndex, type).store(loadStrategy.genValue(parameterDescriptor, codegen), iv);

                iv.mark(loadArg);
            }

            generator.putValueIfNeeded(parameterDescriptor, type, StackValue.local(parameterIndex, type));
        }

        CallableMethod method = state.getTypeMapper().mapToCallableMethod(functionDescriptor, false);

        generator.genCallWithoutAssertions(method, codegen);

        iv.areturn(signature.getReturnType());
    }

    @NotNull
    private static FrameMap createFrameMap(
            @NotNull GenerationState state,
            @NotNull FunctionDescriptor function,
            @NotNull JvmMethodSignature signature,
            boolean isStatic
    ) {
        FrameMap frameMap = new FrameMap();
        if (!isStatic) {
            frameMap.enterTemp(OBJECT_TYPE);
        }

        for (JvmMethodParameterSignature parameter : signature.getValueParameters()) {
            if (parameter.getKind() == JvmMethodParameterKind.RECEIVER) {
                ReceiverParameterDescriptor receiverParameter = function.getExtensionReceiverParameter();
                if (receiverParameter != null) {
                    frameMap.enter(receiverParameter, state.getTypeMapper().mapType(receiverParameter));
                }
            }
            else if (parameter.getKind() != JvmMethodParameterKind.VALUE) {
                frameMap.enterTemp(parameter.getAsmType());
            }
        }

        for (ValueParameterDescriptor parameter : function.getValueParameters()) {
            frameMap.enter(parameter, state.getTypeMapper().mapType(parameter));
        }

        return frameMap;
    }

    private static void loadExplicitArgumentsOnStack(
            @NotNull Type ownerType,
            boolean isStatic,
            @NotNull JvmMethodSignature signature,
            @NotNull CallGenerator callGenerator
    ) {
        int var = 0;
        if (!isStatic) {
            callGenerator.putValueIfNeeded(null, ownerType, StackValue.local(var, ownerType));
            var += ownerType.getSize();
        }

        for (JvmMethodParameterSignature parameterSignature : signature.getValueParameters()) {
            if (parameterSignature.getKind() != JvmMethodParameterKind.VALUE) {
                Type type = parameterSignature.getAsmType();
                callGenerator.putValueIfNeeded(null, type, StackValue.local(var, type));
                var += type.getSize();
            }
        }
    }

    private static boolean isDefaultNeeded(FunctionDescriptor functionDescriptor) {
        boolean needed = false;
        if (functionDescriptor != null) {
            for (ValueParameterDescriptor parameterDescriptor : functionDescriptor.getValueParameters()) {
                if (parameterDescriptor.declaresDefaultValue()) {
                    needed = true;
                    break;
                }
            }
        }
        return needed;
    }

    private void generateBridge(
            @Nullable PsiElement origin,
            @NotNull FunctionDescriptor descriptor,
            @NotNull Method bridge,
            @NotNull Method delegateTo,
            boolean isSpecialBridge,
            boolean isStubDeclarationWithDelegationToSuper
    ) {
        boolean isSpecialOrDelegationToSuper = isSpecialBridge || isStubDeclarationWithDelegationToSuper;
        int flags = ACC_PUBLIC | ACC_BRIDGE | (!isSpecialOrDelegationToSuper ? ACC_SYNTHETIC : 0) | (isSpecialBridge ? ACC_FINAL : 0); // TODO.

        MethodVisitor mv =
                v.newMethod(JvmDeclarationOriginKt.Bridge(descriptor, origin), flags, bridge.getName(), bridge.getDescriptor(), null, null);
        if (state.getClassBuilderMode() != ClassBuilderMode.FULL) return;

        mv.visitCode();

        Type[] argTypes = bridge.getArgumentTypes();
        Type[] originalArgTypes = delegateTo.getArgumentTypes();

        InstructionAdapter iv = new InstructionAdapter(mv);
        MemberCodegen.markLineNumberForSyntheticFunction(owner.getThisDescriptor(), iv);

        generateInstanceOfBarrierIfNeeded(iv, descriptor, bridge, delegateTo);

        iv.load(0, OBJECT_TYPE);
        for (int i = 0, reg = 1; i < argTypes.length; i++) {
            StackValue.local(reg, argTypes[i]).put(originalArgTypes[i], iv);
            //noinspection AssignmentToForLoopParameter
            reg += argTypes[i].getSize();
        }

        if (isStubDeclarationWithDelegationToSuper) {
            ClassDescriptor parentClass = getSuperClassDescriptor((ClassDescriptor) descriptor.getContainingDeclaration());
            assert parentClass != null;
            String parentInternalName = typeMapper.mapClass(parentClass).getInternalName();
            iv.invokespecial(parentInternalName, delegateTo.getName(), delegateTo.getDescriptor());
        }
        else {
            iv.invokevirtual(v.getThisName(), delegateTo.getName(), delegateTo.getDescriptor());
        }

        StackValue.coerce(delegateTo.getReturnType(), bridge.getReturnType(), iv);
        iv.areturn(bridge.getReturnType());

        endVisit(mv, "bridge method", origin);
    }

    private static void generateInstanceOfBarrierIfNeeded(
            @NotNull InstructionAdapter iv,
            @NotNull FunctionDescriptor descriptor,
            @NotNull Method bridge,
            @NotNull Method delegateTo
    ) {
        if (BuiltinMethodsWithSpecialGenericSignature.getOverriddenBuiltinFunctionWithErasedValueParametersInJava(descriptor) == null) return;

        assert descriptor.getValueParameters().size() == 1 : "Should be descriptor with one value parameter, but found: " + descriptor;

        if (bridge.getArgumentTypes()[0].getSort() != Type.OBJECT) return;

        iv.load(1, OBJECT_TYPE);

        KotlinType jetType = descriptor.getValueParameters().get(0).getType();

        // TODO: reuse logic from ExpressionCodegen
        if (jetType.isMarkedNullable()) {
            Label nope = new Label();
            Label end = new Label();

            iv.dup();
            iv.ifnull(nope);
            TypeIntrinsics.instanceOf(
                    iv,
                    jetType,
                    boxType(delegateTo.getArgumentTypes()[0])
            );
            iv.goTo(end);
            iv.mark(nope);
            iv.pop();
            iv.iconst(1);
            iv.mark(end);
        }
        else {
            TypeIntrinsics.instanceOf(
                    iv,
                    jetType,
                    boxType(delegateTo.getArgumentTypes()[0])
            );
        }

        Label afterBarrier = new Label();

        iv.ifne(afterBarrier);

        String shortName = getFqName(descriptor).shortName().asString();
        StackValue returnValue =
                ("indexOf".equals(shortName) || "lastIndexOf".equals(shortName)) ? StackValue.constant(-1, Type.INT_TYPE) : StackValue.none();

        returnValue.put(bridge.getReturnType(), iv);
        iv.areturn(bridge.getReturnType());

        iv.visitLabel(afterBarrier);
    }

    public void genDelegate(@NotNull FunctionDescriptor functionDescriptor, FunctionDescriptor overriddenDescriptor, StackValue field) {
        genDelegate(functionDescriptor, overriddenDescriptor.getOriginal(),
                    (ClassDescriptor) overriddenDescriptor.getContainingDeclaration(), field);
    }

    public void genDelegate(
            @NotNull final FunctionDescriptor delegateFunction,
            final FunctionDescriptor delegatedTo,
            final ClassDescriptor toClass,
            final StackValue field
    ) {
        generateMethod(
                JvmDeclarationOriginKt.Delegation(DescriptorToSourceUtils.descriptorToDeclaration(delegatedTo), delegateFunction), delegateFunction,
                new FunctionGenerationStrategy() {
                    @Override
                    public void generateBody(
                            @NotNull MethodVisitor mv,
                            @NotNull FrameMap frameMap,
                            @NotNull JvmMethodSignature signature,
                            @NotNull MethodContext context,
                            @NotNull MemberCodegen<?> parentCodegen
                    ) {
                        Method delegateToMethod = typeMapper.mapSignature(delegatedTo).getAsmMethod();
                        Method delegateMethod = typeMapper.mapSignature(delegateFunction).getAsmMethod();

                        Type[] argTypes = delegateMethod.getArgumentTypes();
                        Type[] originalArgTypes = delegateToMethod.getArgumentTypes();

                        InstructionAdapter iv = new InstructionAdapter(mv);
                        iv.load(0, OBJECT_TYPE);
                        field.put(field.type, iv);
                        for (int i = 0, reg = 1; i < argTypes.length; i++) {
                            StackValue.local(reg, argTypes[i]).put(originalArgTypes[i], iv);
                            //noinspection AssignmentToForLoopParameter
                            reg += argTypes[i].getSize();
                        }

                        String internalName = typeMapper.mapType(toClass).getInternalName();
                        if (toClass.getKind() == ClassKind.INTERFACE) {
                            iv.invokeinterface(internalName, delegateToMethod.getName(), delegateToMethod.getDescriptor());
                        }
                        else {
                            iv.invokevirtual(internalName, delegateToMethod.getName(), delegateToMethod.getDescriptor());
                        }

                        StackValue stackValue = AsmUtil.genNotNullAssertions(
                                state,
                                StackValue.onStack(delegateToMethod.getReturnType()),
                                RuntimeAssertionInfo.create(
                                        delegateFunction.getReturnType(),
                                        delegatedTo.getReturnType(),
                                        new RuntimeAssertionInfo.DataFlowExtras.OnlyMessage(delegatedTo.getName() + "(...)")
                                )
                        );

                        stackValue.put(delegateMethod.getReturnType(), iv);

                        iv.areturn(delegateMethod.getReturnType());
                    }
                }
        );
    }
}
