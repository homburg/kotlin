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

package org.jetbrains.kotlin.codegen.context;

import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.codegen.*;
import org.jetbrains.kotlin.codegen.binding.MutableClosure;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.codegen.state.JetTypeMapper;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.load.java.JavaVisibilities;
import org.jetbrains.kotlin.load.java.descriptors.SamConstructorDescriptor;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.storage.LockBasedStorageManager;
import org.jetbrains.kotlin.storage.NullableLazyValue;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeUtils;
import org.jetbrains.org.objectweb.asm.Type;

import java.util.*;

import static org.jetbrains.kotlin.codegen.AsmUtil.getVisibilityAccessFlag;
import static org.jetbrains.kotlin.resolve.BindingContext.NEED_SYNTHETIC_ACCESSOR;
import static org.jetbrains.org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.jetbrains.org.objectweb.asm.Opcodes.ACC_PROTECTED;

public abstract class CodegenContext<T extends DeclarationDescriptor> {
    private final T contextDescriptor;
    private final OwnerKind contextKind;
    private final CodegenContext parentContext;
    private final ClassDescriptor thisDescriptor;
    public final MutableClosure closure;
    private final LocalLookup enclosingLocalLookup;
    private final NullableLazyValue<StackValue.Field> outerExpression;

    private Map<DeclarationDescriptor, CodegenContext> childContexts;
    private Map<AccessorKey, AccessorForCallableDescriptor<?>> accessors;
    private Map<AccessorKey, AccessorForPropertyDescriptorFactory> propertyAccessorFactories;

    private static class AccessorKey {
        public final DeclarationDescriptor descriptor;
        public final ClassDescriptor superCallLabelTarget;

        public AccessorKey(
                @NotNull DeclarationDescriptor descriptor,
                @Nullable ClassDescriptor superCallLabelTarget
        ) {
            this.descriptor = descriptor;
            this.superCallLabelTarget = superCallLabelTarget;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AccessorKey)) return false;
            AccessorKey other = (AccessorKey) obj;
            return descriptor.equals(other.descriptor) &&
                   (superCallLabelTarget == null ? other.superCallLabelTarget == null
                                                 : superCallLabelTarget.equals(other.superCallLabelTarget));
        }

        @Override
        public int hashCode() {
            return 31 * descriptor.hashCode() + (superCallLabelTarget == null ? 0 : superCallLabelTarget.hashCode());
        }

        @Override
        public String toString() {
            return descriptor.toString();
        }
    }

    private static class AccessorForPropertyDescriptorFactory {
        private final @NotNull PropertyDescriptor property;
        private final @NotNull DeclarationDescriptor containingDeclaration;
        private final @Nullable ClassDescriptor superCallTarget;
        private final @NotNull String nameSuffix;

        private AccessorForPropertyDescriptor withSyntheticGetterAndSetter = null;
        private AccessorForPropertyDescriptor withSyntheticGetter = null;
        private AccessorForPropertyDescriptor withSyntheticSetter = null;

        public AccessorForPropertyDescriptorFactory(
                @NotNull PropertyDescriptor property,
                @NotNull DeclarationDescriptor containingDeclaration,
                @Nullable ClassDescriptor superCallTarget,
                @NotNull String nameSuffix
        ) {
            this.property = property;
            this.containingDeclaration = containingDeclaration;
            this.superCallTarget = superCallTarget;
            this.nameSuffix = nameSuffix;
        }

        @SuppressWarnings("ConstantConditions")
        public PropertyDescriptor getOrCreateAccessorIfNeeded(boolean getterAccessorRequired, boolean setterAccessorRequired) {
            if (getterAccessorRequired && setterAccessorRequired) {
                return getOrCreateAccessorWithSyntheticGetterAndSetter();
            }
            else if (getterAccessorRequired && !setterAccessorRequired) {
                if (withSyntheticGetter == null) {
                    withSyntheticGetter = new AccessorForPropertyDescriptor(
                            property, containingDeclaration, superCallTarget, nameSuffix,
                            true, false);
                }
                return withSyntheticGetter;
            }
            else if (!getterAccessorRequired && setterAccessorRequired) {
                if (withSyntheticSetter == null) {
                    withSyntheticSetter = new AccessorForPropertyDescriptor(
                            property, containingDeclaration, superCallTarget, nameSuffix,
                            false, true);
                }
                return withSyntheticSetter;
            }
            else {
                return property;
            }
        }

        @NotNull
        public AccessorForPropertyDescriptor getOrCreateAccessorWithSyntheticGetterAndSetter() {
            if (withSyntheticGetterAndSetter == null) {
                withSyntheticGetterAndSetter = new AccessorForPropertyDescriptor(
                        property, containingDeclaration, superCallTarget, nameSuffix,
                        true, true);
            }
            return withSyntheticGetterAndSetter;
        }
    }

    public CodegenContext(
            @NotNull T contextDescriptor,
            @NotNull OwnerKind contextKind,
            @Nullable CodegenContext parentContext,
            @Nullable MutableClosure closure,
            @Nullable ClassDescriptor thisDescriptor,
            @Nullable LocalLookup localLookup
    ) {
        this.contextDescriptor = contextDescriptor;
        this.contextKind = contextKind;
        this.parentContext = parentContext;
        this.closure = closure;
        this.thisDescriptor = thisDescriptor;
        this.enclosingLocalLookup = localLookup;
        this.outerExpression = LockBasedStorageManager.NO_LOCKS.createNullableLazyValue(new Function0<StackValue.Field>() {
            @Override
            public StackValue.Field invoke() {
                return computeOuterExpression();
            }
        });

        if (parentContext != null) {
            parentContext.addChild(this);
        }
    }

    @NotNull
    public GenerationState getState() {
        return parentContext.getState();
    }

    @NotNull
    public final ClassDescriptor getThisDescriptor() {
        if (thisDescriptor == null) {
            throw new UnsupportedOperationException("Context doesn't have a \"this\": " + this);
        }
        return thisDescriptor;
    }

    public final boolean hasThisDescriptor() {
        return thisDescriptor != null;
    }

    @NotNull
    @SuppressWarnings("unchecked")
    public CodegenContext<? extends ClassOrPackageFragmentDescriptor> getClassOrPackageParentContext() {
        CodegenContext<?> context = this;
        while (true) {
            if (context.getContextDescriptor() instanceof ClassOrPackageFragmentDescriptor) {
                return (CodegenContext) context;
            }
            context = context.getParentContext();
            assert context != null : "Context which is not top-level has no parent: " + this;
        }
    }

    /**
     * This method returns not null only if context descriptor corresponds to method or function which has receiver
     */
    @Nullable
    public final CallableDescriptor getCallableDescriptorWithReceiver() {
        if (contextDescriptor instanceof CallableDescriptor) {
            CallableDescriptor callableDescriptor = (CallableDescriptor) getContextDescriptor();
            return callableDescriptor.getExtensionReceiverParameter() != null ? callableDescriptor : null;
        }
        return null;
    }

    public StackValue getOuterExpression(@Nullable StackValue prefix, boolean ignoreNoOuter) {
        return getOuterExpression(prefix, ignoreNoOuter, true);
    }

    private StackValue getOuterExpression(@Nullable StackValue prefix, boolean ignoreNoOuter, boolean captureThis) {
        if (outerExpression.invoke() == null) {
            if (!ignoreNoOuter) {
                throw new UnsupportedOperationException("Don't know how to generate outer expression for " + getContextDescriptor());
            }
            return null;
        }
        if (captureThis) {
            if (closure == null) {
                throw new IllegalStateException("Can't capture this for context without closure: " + getContextDescriptor());
            }
            closure.setCaptureThis();
        }
        return StackValue.changeReceiverForFieldAndSharedVar(outerExpression.invoke(), prefix);
    }

    @NotNull
    public T getContextDescriptor() {
        return contextDescriptor;
    }

    @NotNull
    public OwnerKind getContextKind() {
        return contextKind;
    }

    @NotNull
    public PackageContext intoPackagePart(@NotNull PackageFragmentDescriptor descriptor, Type packagePartType, @Nullable KtFile sourceFile) {
        return new PackageContext(descriptor, this, packagePartType, sourceFile);
    }

    @NotNull
    public FieldOwnerContext<PackageFragmentDescriptor> intoMultifileClassPart(
            @NotNull PackageFragmentDescriptor descriptor,
            @NotNull Type multifileClassType,
            @NotNull Type filePartType,
            @NotNull KtFile sourceFile
    ) {
        return new MultifileClassPartContext(descriptor, this, multifileClassType, filePartType, sourceFile);
    }

    @NotNull
    public FieldOwnerContext<PackageFragmentDescriptor> intoMultifileClass(
            @NotNull PackageFragmentDescriptor descriptor,
            @NotNull Type multifileClassType,
            @NotNull Type filePartType
    ) {
        return new MultifileClassFacadeContext(descriptor, this, multifileClassType, filePartType);
    }

    @NotNull
    public ClassContext intoClass(ClassDescriptor descriptor, OwnerKind kind, GenerationState state) {
        if (descriptor.isCompanionObject()) {
            CodegenContext companionContext = this.findChildContext(descriptor);
            if (companionContext != null) {
                assert companionContext.getContextKind() == kind : "Kinds should be same, but: " +
                                                                   companionContext.getContextKind() + "!= " + kind;
                return (ClassContext) companionContext;
            }
        }
        ClassContext classContext = new ClassContext(state.getTypeMapper(), descriptor, kind, this, null);

        //We can't call descriptor.getCompanionObjectDescriptor() on light class generation
        // because it triggers companion light class generation via putting it to BindingContext.CLASS
        // (so MemberCodegen doesn't skip it in genClassOrObject).
        if (state.getTypeMapper().getClassBuilderMode() != ClassBuilderMode.LIGHT_CLASSES &&
            descriptor.getCompanionObjectDescriptor() != null) {
            //We need to create companion object context ahead of time
            // because otherwise we can't generate synthetic accessor for private members in companion object
            classContext.intoClass(descriptor.getCompanionObjectDescriptor(), OwnerKind.IMPLEMENTATION, state);
        }
        return classContext;
    }

    @NotNull
    public ClassContext intoAnonymousClass(@NotNull ClassDescriptor descriptor, @NotNull ExpressionCodegen codegen, @NotNull OwnerKind ownerKind) {
        return new AnonymousClassContext(codegen.getState().getTypeMapper(), descriptor, ownerKind, this, codegen);
    }

    @NotNull
    public MethodContext intoFunction(FunctionDescriptor descriptor) {
        return new MethodContext(descriptor, getContextKind(), this, null, false);
    }

    @NotNull
    public MethodContext intoInlinedLambda(FunctionDescriptor descriptor) {
        return new MethodContext(descriptor, getContextKind(), this, null, true);
    }

    @NotNull
    public ConstructorContext intoConstructor(@NotNull ConstructorDescriptor descriptor) {
        return new ConstructorContext(descriptor, getContextKind(), this, closure);
    }

    // SCRIPT: generate into script, move to ScriptingUtil
    @NotNull
    public ScriptContext intoScript(
            @NotNull ScriptDescriptor script,
            @NotNull List<ScriptDescriptor> earlierScripts,
            @NotNull ClassDescriptor classDescriptor
    ) {
        return new ScriptContext(script, earlierScripts, classDescriptor, OwnerKind.IMPLEMENTATION, this, closure);
    }

    @NotNull
    public ClosureContext intoClosure(
            @NotNull FunctionDescriptor funDescriptor,
            @NotNull LocalLookup localLookup,
            @NotNull JetTypeMapper typeMapper
    ) {
        return new ClosureContext(typeMapper, funDescriptor, this, localLookup);
    }

    @Nullable
    public CodegenContext getParentContext() {
        return parentContext;
    }

    public ClassDescriptor getEnclosingClass() {
        CodegenContext cur = getParentContext();
        while (cur != null && !(cur.getContextDescriptor() instanceof ClassDescriptor)) {
            cur = cur.getParentContext();
        }

        return cur == null ? null : (ClassDescriptor) cur.getContextDescriptor();
    }

    @Nullable
    public CodegenContext findParentContextWithDescriptor(DeclarationDescriptor descriptor) {
        CodegenContext c = this;
        while (c != null) {
            if (c.getContextDescriptor() == descriptor) break;
            c = c.getParentContext();
        }
        return c;
    }

    @NotNull
    private PropertyDescriptor getPropertyAccessor(
            @NotNull PropertyDescriptor propertyDescriptor,
            @Nullable ClassDescriptor superCallTarget,
            boolean getterAccessorRequired,
            boolean setterAccessorRequired
    ) {
        return getAccessor(propertyDescriptor, FieldAccessorKind.NORMAL, null, superCallTarget, getterAccessorRequired, setterAccessorRequired);
    }

    @NotNull
    public <D extends CallableMemberDescriptor> D getAccessor(@NotNull D descriptor, @Nullable ClassDescriptor superCallTarget) {
        return getAccessor(descriptor, FieldAccessorKind.NORMAL, null, superCallTarget);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    public <D extends CallableMemberDescriptor> D getAccessor(
            @NotNull D possiblySubstitutedDescriptor,
            @NotNull FieldAccessorKind accessorKind,
            @Nullable KotlinType delegateType,
            @Nullable ClassDescriptor superCallTarget
    ) {
        // TODO this corresponds to default behavior for properties before fixing KT-9717. Is it Ok in general case?
        // Does not matter for other descriptor kinds.
        return getAccessor(possiblySubstitutedDescriptor, accessorKind, delegateType, superCallTarget,
                           /* getterAccessorRequired */ true,
                           /* setterAccessorRequired */ true);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private <D extends CallableMemberDescriptor> D getAccessor(
            @NotNull D possiblySubstitutedDescriptor,
            @NotNull FieldAccessorKind accessorKind,
            @Nullable KotlinType delegateType,
            @Nullable ClassDescriptor superCallTarget,
            boolean getterAccessorRequired,
            boolean setterAccessorRequired
    ) {
        if (accessors == null) {
            accessors = new LinkedHashMap<AccessorKey, AccessorForCallableDescriptor<?>>();
        }
        if (propertyAccessorFactories == null) {
            propertyAccessorFactories = new LinkedHashMap<AccessorKey, AccessorForPropertyDescriptorFactory>();
        }

        D descriptor = (D) possiblySubstitutedDescriptor.getOriginal();
        AccessorKey key = new AccessorKey(descriptor, superCallTarget);

        // NB should check for property accessor factory first (or change property accessor tracking under propertyAccessorFactory creation)
        AccessorForPropertyDescriptorFactory propertyAccessorFactory = propertyAccessorFactories.get(key);
        if (propertyAccessorFactory != null) {
            return (D) propertyAccessorFactory.getOrCreateAccessorIfNeeded(getterAccessorRequired, setterAccessorRequired);
        }
        AccessorForCallableDescriptor<?> accessor = accessors.get(key);
        if (accessor != null) {
            assert accessorKind == FieldAccessorKind.NORMAL ||
                   accessor instanceof AccessorForPropertyBackingField : "There is already exists accessor with isForBackingField = false in this context";
            return (D) accessor;
        }
        String nameSuffix = SyntheticAccessorUtilKt.getAccessorNameSuffix(descriptor, key.superCallLabelTarget, accessorKind);
        if (descriptor instanceof SimpleFunctionDescriptor) {
            accessor = new AccessorForFunctionDescriptor(
                    (FunctionDescriptor) descriptor, contextDescriptor, superCallTarget, nameSuffix
            );
        }
        else if (descriptor instanceof ConstructorDescriptor) {
            accessor = new AccessorForConstructorDescriptor((ConstructorDescriptor) descriptor, contextDescriptor, superCallTarget);
        }
        else if (descriptor instanceof PropertyDescriptor) {
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) descriptor;
            switch (accessorKind) {
                case NORMAL:
                    propertyAccessorFactory = new AccessorForPropertyDescriptorFactory((PropertyDescriptor) descriptor, contextDescriptor,
                                                                                       superCallTarget, nameSuffix);
                    propertyAccessorFactories.put(key, propertyAccessorFactory);

                    // Record worst case accessor for accessor methods generation.
                    AccessorForPropertyDescriptor accessorWithGetterAndSetter =
                            propertyAccessorFactory.getOrCreateAccessorWithSyntheticGetterAndSetter();
                    accessors.put(key, accessorWithGetterAndSetter);

                    PropertyDescriptor accessorDescriptor =
                            propertyAccessorFactory.getOrCreateAccessorIfNeeded(getterAccessorRequired, setterAccessorRequired);
                    return (D) accessorDescriptor;
                case IN_CLASS_COMPANION:
                    accessor = new AccessorForPropertyBackingFieldInClassCompanion(propertyDescriptor, contextDescriptor,
                                                                                   delegateType, nameSuffix);
                    break;
                case FIELD_FROM_LOCAL:
                    accessor = new AccessorForPropertyBackingFieldFromLocal(propertyDescriptor, contextDescriptor, nameSuffix);
                    break;
            }
        }
        else {
            throw new UnsupportedOperationException("Do not know how to create accessor for descriptor " + descriptor);
        }

        accessors.put(key, accessor);

        return (D) accessor;
    }

    @Nullable
    protected StackValue.Field computeOuterExpression() {
        return null;
    }

    public StackValue lookupInContext(DeclarationDescriptor d, @Nullable StackValue result, GenerationState state, boolean ignoreNoOuter) {
        StackValue myOuter = null;
        if (closure != null) {
            EnclosedValueDescriptor answer = closure.getCaptureVariables().get(d);
            if (answer != null) {
                return StackValue.changeReceiverForFieldAndSharedVar(answer.getInnerValue(), result);
            }

            for (LocalLookup.LocalLookupCase aCase : LocalLookup.LocalLookupCase.values()) {
                if (aCase.isCase(d)) {
                    Type classType = state.getTypeMapper().mapType(getThisDescriptor());
                    StackValue.StackValueWithSimpleReceiver innerValue = aCase.innerValue(d, enclosingLocalLookup, state, closure, classType);
                    if (innerValue == null) {
                        break;
                    }
                    else {
                        return StackValue.changeReceiverForFieldAndSharedVar(innerValue, result);
                    }
                }
            }

            myOuter = getOuterExpression(result, ignoreNoOuter, false);
            result = myOuter;
        }

        StackValue resultValue;
        if (myOuter != null && getEnclosingClass() == d) {
            resultValue = result;
        } else {
            resultValue = parentContext != null ? parentContext.lookupInContext(d, result, state, ignoreNoOuter) : null;
        }

        if (myOuter != null && resultValue != null && !isStaticField(resultValue)) {
            closure.setCaptureThis();
        }
        return resultValue;
    }

    @NotNull
    public Collection<? extends AccessorForCallableDescriptor<?>> getAccessors() {
        return accessors == null ? Collections.<AccessorForCallableDescriptor<CallableMemberDescriptor>>emptySet() : accessors.values();
    }

    @NotNull
    public <D extends CallableMemberDescriptor> D accessibleDescriptor(
            @NotNull D descriptor,
            @Nullable ClassDescriptor superCallTarget
    ) {
        DeclarationDescriptor enclosing = descriptor.getContainingDeclaration();
        if (!isInlineMethodContext() && (
                !hasThisDescriptor() ||
                enclosing == getThisDescriptor() ||
                enclosing == getClassOrPackageParentContext().getContextDescriptor())) {
            return descriptor;
        }

        return accessibleDescriptorIfNeeded(descriptor, superCallTarget);
    }

    public void recordSyntheticAccessorIfNeeded(@NotNull CallableMemberDescriptor descriptor, @NotNull BindingContext bindingContext) {
        if (hasThisDescriptor() && Boolean.TRUE.equals(bindingContext.get(NEED_SYNTHETIC_ACCESSOR, descriptor))) {
            // Not a super call because neither constructors nor private members can be targets of super calls
            accessibleDescriptorIfNeeded(descriptor, /* superCallTarget = */ null);
        }
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private <D extends CallableMemberDescriptor> D accessibleDescriptorIfNeeded(
            @NotNull D descriptor,
            @Nullable ClassDescriptor superCallTarget
    ) {
        CallableMemberDescriptor unwrappedDescriptor = DescriptorUtils.unwrapFakeOverride(descriptor);

        DeclarationDescriptor enclosed = descriptor.getContainingDeclaration();
        CodegenContext descriptorContext = findParentContextWithDescriptor(enclosed);
        if (descriptorContext == null && DescriptorUtils.isCompanionObject(enclosed)) {
            CodegenContext classContext = findParentContextWithDescriptor(enclosed.getContainingDeclaration());
            if (classContext instanceof ClassContext) {
                descriptorContext = ((ClassContext) classContext).getCompanionObjectContext();
            }
        }

        if (descriptorContext == null &&
            JavaVisibilities.PROTECTED_STATIC_VISIBILITY == descriptor.getVisibility() &&
            !(descriptor instanceof SamConstructorDescriptor)) {
            //seems we need static receiver in resolved call
            descriptorContext = ExpressionCodegen.getParentContextSubclassOf((ClassDescriptor) enclosed, this);
            superCallTarget = (ClassDescriptor) enclosed;
        }

        if (descriptorContext == null) {
            return descriptor;
        }

        if (descriptor instanceof PropertyDescriptor) {
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) descriptor;
            int propertyAccessFlag = getVisibilityAccessFlag(descriptor);

            PropertyGetterDescriptor getter = propertyDescriptor.getGetter();
            int getterAccessFlag = getter == null ? propertyAccessFlag
                                                  : propertyAccessFlag | getVisibilityAccessFlag(getter);
            boolean getterAccessorRequired = isAccessorRequired(getterAccessFlag, unwrappedDescriptor, descriptorContext);

            PropertySetterDescriptor setter = propertyDescriptor.getSetter();
            int setterAccessFlag = setter == null ? propertyAccessFlag
                                                  : propertyAccessFlag | getVisibilityAccessFlag(setter);
            boolean setterAccessorRequired = isAccessorRequired(setterAccessFlag, unwrappedDescriptor, descriptorContext);

            if (!getterAccessorRequired && !setterAccessorRequired) {
                return descriptor;
            }
            return (D) descriptorContext.getPropertyAccessor(propertyDescriptor, superCallTarget, getterAccessorRequired, setterAccessorRequired);
        }
        else {
            int flag = getVisibilityAccessFlag(unwrappedDescriptor);
            if (!isAccessorRequired(flag, unwrappedDescriptor, descriptorContext)) {
                return descriptor;
            }
            return (D) descriptorContext.getAccessor(descriptor, superCallTarget);
        }
    }

    private static boolean isAccessorRequired(
            int accessFlag,
            @NotNull CallableMemberDescriptor unwrappedDescriptor,
            @NotNull CodegenContext descriptorContext
    ) {
        return (accessFlag & ACC_PRIVATE) != 0 ||
               ((accessFlag & ACC_PROTECTED) != 0 && !isInSamePackage(unwrappedDescriptor, descriptorContext.getContextDescriptor()));
    }

    private static boolean isInSamePackage(DeclarationDescriptor descriptor1, DeclarationDescriptor descriptor2) {
        PackageFragmentDescriptor package1 =
                DescriptorUtils.getParentOfType(descriptor1, PackageFragmentDescriptor.class, false);
        PackageFragmentDescriptor package2 =
                DescriptorUtils.getParentOfType(descriptor2, PackageFragmentDescriptor.class, false);

        return package2 != null && package1 != null &&
               package1.getFqName().equals(package2.getFqName());
    }

    private void addChild(@NotNull CodegenContext child) {
        if (shouldAddChild(child)) {
            if (childContexts == null) {
                childContexts = new HashMap<DeclarationDescriptor, CodegenContext>();
            }
            DeclarationDescriptor childContextDescriptor = child.getContextDescriptor();
            childContexts.put(childContextDescriptor, child);
        }
    }

    protected boolean shouldAddChild(@NotNull CodegenContext child) {
        return DescriptorUtils.isCompanionObject(child.contextDescriptor);
    }

    @Nullable
    public CodegenContext findChildContext(@NotNull DeclarationDescriptor child) {
        return childContexts == null ? null : childContexts.get(child);
    }

    private static boolean isStaticField(@NotNull StackValue value) {
        return value instanceof StackValue.Field && ((StackValue.Field) value).isStaticPut;
    }

    private boolean isInsideInliningContext() {
        CodegenContext current = this;
        while (current != null) {
            if (current instanceof MethodContext && ((MethodContext) current).isInlineFunction()) {
                return true;
            }
            current = current.getParentContext();
        }
        return false;
    }

    private boolean isInlineMethodContext() {
        return this instanceof MethodContext && ((MethodContext) this).isInlineFunction();
    }
}
