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

package org.jetbrains.kotlin.renderer

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils.isCompanionObject
import org.jetbrains.kotlin.resolve.constants.AnnotationValue
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.descriptorUtil.hasDefaultValue
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.ErrorUtils.UninferredParameterTypeConstructor
import org.jetbrains.kotlin.types.TypeUtils.CANT_INFER_FUNCTION_PARAM_TYPE
import org.jetbrains.kotlin.types.error.MissingDependencyErrorClass
import java.util.*

internal class DescriptorRendererImpl(
        val options: DescriptorRendererOptionsImpl
) : DescriptorRenderer(), DescriptorRendererOptions by options/* this gives access to options without qualifier */ {

    init {
        assert(options.isLocked)
    }

    /* FORMATTING */
    private fun renderKeyword(keyword: String): String {
        when (textFormat) {
            RenderingFormat.PLAIN -> return keyword
            RenderingFormat.HTML -> return "<b>" + keyword + "</b>"
        }
    }

    private fun renderError(keyword: String): String {
        when (textFormat) {
            RenderingFormat.PLAIN -> return keyword
            RenderingFormat.HTML -> return "<font color=red><b>" + keyword + "</b></font>"
        }
    }

    private fun escape(string: String): String {
        when (textFormat) {
            RenderingFormat.PLAIN -> return string
            RenderingFormat.HTML -> return string.replace("<", "&lt;").replace(">", "&gt;")
        }
    }

    private fun lt() = escape("<")
    private fun gt() = escape(">")

    private fun arrow(): String {
        return when (textFormat) {
            RenderingFormat.PLAIN -> escape("->")
            RenderingFormat.HTML -> "&rarr;"
        }
    }

    private fun renderMessage(message: String): String {
        return when (textFormat) {
            RenderingFormat.PLAIN -> message
            RenderingFormat.HTML -> "<i>$message</i>"
        }
    }

    /* NAMES RENDERING */
    override fun renderName(name: Name): String {
        return escape(name.render())
    }

    private fun renderName(descriptor: DeclarationDescriptor, builder: StringBuilder) {
        builder.append(renderName(descriptor.getName()))
    }

    private fun renderCompanionObjectName(descriptor: DeclarationDescriptor, builder: StringBuilder) {
        if (renderCompanionObjectName) {
            if (startFromName) {
                builder.append("companion object")
            }
            renderSpaceIfNeeded(builder)
            val containingDeclaration = descriptor.getContainingDeclaration()
            if (containingDeclaration != null) {
                builder.append("of ")
                builder.append(renderName(containingDeclaration.getName()))
            }
        }
        if (verbose || descriptor.name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            if (!startFromName) renderSpaceIfNeeded(builder)
            builder.append(renderName(descriptor.getName()))
        }
    }

    override fun renderFqName(fqName: FqNameUnsafe) = renderFqName(fqName.pathSegments())

    private fun renderFqName(pathSegments: List<Name>) = escape(org.jetbrains.kotlin.renderer.renderFqName(pathSegments))

    override fun renderClassifierName(klass: ClassifierDescriptor): String {
        if (klass is MissingDependencyErrorClass) {
            return klass.fullFqName.asString()
        }
        if (ErrorUtils.isError(klass)) {
            return klass.getTypeConstructor().toString()
        }
        when (nameShortness) {
            NameShortness.SHORT -> {
                val qualifiedNameElements = ArrayList<Name>()

                // for nested classes qualified name should be used
                var current: DeclarationDescriptor? = klass
                do {
                    qualifiedNameElements.add(current!!.getName())
                    current = current.getContainingDeclaration()
                }
                while (current is ClassDescriptor)

                return renderFqName(qualifiedNameElements.asReversed())
            }

            NameShortness.FULLY_QUALIFIED -> return renderFqName(DescriptorUtils.getFqName(klass))

            NameShortness.SOURCE_CODE_QUALIFIED -> return qualifiedNameForSourceCode(klass)

            else -> throw IllegalArgumentException()
        }
    }

    /* TYPES RENDERING */
    override fun renderType(type: KotlinType): String {
        return renderNormalizedType(typeNormalizer(type))
    }

    private fun renderNormalizedType(type: KotlinType): String {
        if (type is LazyType && debugMode) {
            return type.toString()
        }
        if (type.isDynamic()) {
            return "dynamic"
        }
        if (type.isFlexible()) {
            if (debugMode) {
                return renderFlexibleTypeWithBothBounds(type.flexibility().lowerBound, type.flexibility().upperBound)
            }
            else if (flexibleTypesForCode) {
                val prefix = if (nameShortness == NameShortness.SHORT) "" else Flexibility.FLEXIBLE_TYPE_CLASSIFIER.getPackageFqName().asString() + "."
                return prefix + Flexibility.FLEXIBLE_TYPE_CLASSIFIER.getRelativeClassName() + lt() + renderNormalizedType(type.flexibility().lowerBound) + ", " + renderNormalizedType(type.flexibility().upperBound) + gt()
            }
            else {
                return renderFlexibleType(type)
            }
        }
        return renderInflexibleType(type)
    }

    private fun renderFlexibleTypeWithBothBounds(lower: KotlinType, upper: KotlinType): String {
        return renderFlexibleTypeWithBothBounds(renderNormalizedType(lower), renderNormalizedType(upper))
    }

    private fun renderFlexibleTypeWithBothBounds(lower: String, upper: String) = "($lower..$upper)"

    private fun renderInflexibleType(type: KotlinType): String {
        assert(!type.isFlexible()) { "Flexible types not allowed here: " + renderNormalizedType(type) }

        val customResult = type.getCapability<CustomFlexibleRendering>()?.renderInflexible(type, this)
        if (customResult != null) return customResult

        if (type == CANT_INFER_FUNCTION_PARAM_TYPE || TypeUtils.isDontCarePlaceholder(type)) {
            return "???"
        }
        if (ErrorUtils.isUninferredParameter(type)) {
            if (uninferredTypeParameterAsName) {
                return renderError((type.getConstructor() as UninferredParameterTypeConstructor).getTypeParameterDescriptor().getName().toString())
            }
            return "???"
        }
        if (type.isError()) {
            return renderDefaultType(type)
        }
        if (shouldRenderAsPrettyFunctionType(type)) {
            return renderFunctionType(type)
        }
        return renderDefaultType(type)
    }

    private fun shouldRenderAsPrettyFunctionType(type: KotlinType): Boolean {
        return prettyFunctionTypes && KotlinBuiltIns.isExactFunctionOrExtensionFunctionType(type)
               && type.getArguments().none { it.isStarProjection() }
    }

    private fun renderFlexibleType(type: KotlinType): String {
        val lower = type.flexibility().lowerBound
        val upper = type.flexibility().upperBound

        val (lowerRendered, upperRendered) = type.getCapability<CustomFlexibleRendering>()?.renderBounds(type.flexibility(), this)
                                             ?: Pair(renderInflexibleType(lower), renderInflexibleType(upper))

        if (differsOnlyInNullability(lowerRendered, upperRendered)) {
            if (upperRendered.startsWith("(")) {
                // the case of complex type, e.g. (() -> Unit)?
                return "(" + lowerRendered + ")!"
            }
            return lowerRendered + "!"
        }

        val kotlinPrefix = if (nameShortness != NameShortness.SHORT) "kotlin." else ""
        val mutablePrefix = "Mutable"
        // java.util.List<Foo> -> (Mutable)List<Foo!>!
        val simpleCollection = replacePrefixes(lowerRendered, kotlinPrefix + mutablePrefix, upperRendered, kotlinPrefix, kotlinPrefix + "(" + mutablePrefix + ")")
        if (simpleCollection != null) return simpleCollection
        // java.util.Map.Entry<Foo, Bar> -> (Mutable)Map.(Mutable)Entry<Foo!, Bar!>!
        val mutableEntry = replacePrefixes(lowerRendered, kotlinPrefix + "MutableMap.MutableEntry", upperRendered, kotlinPrefix + "Map.Entry", kotlinPrefix + "(Mutable)Map.(Mutable)Entry")
        if (mutableEntry != null) return mutableEntry

        // Foo[] -> Array<(out) Foo!>!
        val array = replacePrefixes(lowerRendered, kotlinPrefix + escape("Array<"), upperRendered, kotlinPrefix + escape("Array<out "), kotlinPrefix + escape("Array<(out) "))
        if (array != null) return array
        return renderFlexibleTypeWithBothBounds(lowerRendered, upperRendered)
    }

    override fun renderTypeArguments(typeArguments: List<TypeProjection>): String {
        if (typeArguments.isEmpty()) return ""
        return StringBuilder {
            append(lt())
            appendTypeProjections(typeArguments, this)
            append(gt())
        }.toString()
    }

    private fun renderDefaultType(type: KotlinType): String {
        val sb = StringBuilder()

        renderAnnotations(type, sb, /* needBrackets = */ true)

        if (type.isError()) {
            sb.append(type.getConstructor().toString()) // Debug name of an error type is more informative
        }
        else {
            sb.append(renderTypeConstructor(type.getConstructor()))
        }
        sb.append(renderTypeArguments(type.getArguments()))
        if (type.isMarkedNullable()) {
            sb.append("?")
        }
        return sb.toString()
    }

    override fun renderTypeConstructor(typeConstructor: TypeConstructor): String {
        val cd = typeConstructor.getDeclarationDescriptor()
        return when (cd) {
            is TypeParameterDescriptor -> renderName(cd.getName())
            is ClassDescriptor -> renderClassifierName(cd)
            null -> typeConstructor.toString()
            else -> error("Unexpected classifier: " + cd.javaClass)
        }
    }

    override fun renderTypeProjection(typeProjection: TypeProjection) = StringBuilder {
        appendTypeProjections(listOf(typeProjection), this)
    }.toString()

    private fun appendTypeProjections(typeProjections: List<TypeProjection>, builder: StringBuilder) {
        typeProjections.map {
            if (it.isStarProjection()) {
                "*"
            }
            else {
                val type = renderType(it.getType())
                if (it.getProjectionKind() == Variance.INVARIANT) type else "${it.getProjectionKind()} $type"
            }
        }.joinTo(builder, ", ")
    }

    private fun renderFunctionType(type: KotlinType): String {
        return StringBuilder {
            val isNullable = type.isMarkedNullable()
            if (isNullable) append("(")

            val receiverType = KotlinBuiltIns.getReceiverType(type)
            if (receiverType != null) {
                val surroundReceiver = shouldRenderAsPrettyFunctionType(receiverType) && !receiverType.isMarkedNullable()
                if (surroundReceiver) {
                    append("(")
                }
                append(renderNormalizedType(receiverType))
                if (surroundReceiver) {
                    append(")")
                }
                append(".")
            }

            append("(")
            appendTypeProjections(KotlinBuiltIns.getParameterTypeProjectionsFromFunctionType(type), this)
            append(") ").append(arrow()).append(" ")
            append(renderNormalizedType(KotlinBuiltIns.getReturnTypeFromFunctionType(type)))

            if (isNullable) append(")?")
        }.toString()
    }


    /* METHODS FOR ALL KINDS OF DESCRIPTORS */
    private fun appendDefinedIn(descriptor: DeclarationDescriptor, builder: StringBuilder) {
        if (descriptor is PackageFragmentDescriptor || descriptor is PackageViewDescriptor) {
            return
        }
        if (descriptor is ModuleDescriptor) {
            builder.append(" is a module")
            return
        }

        val containingDeclaration = descriptor.containingDeclaration
        if (containingDeclaration != null && containingDeclaration !is ModuleDescriptor) {
            builder.append(" ").append(renderMessage("defined in")).append(" ")
            val fqName = DescriptorUtils.getFqName(containingDeclaration)
            builder.append(if (fqName.isRoot) "root package" else renderFqName(fqName))
        }
    }
    private fun renderAnnotations(annotated: Annotated, builder: StringBuilder, needBrackets: Boolean = false) {
        if (DescriptorRendererModifier.ANNOTATIONS !in modifiers) return

        val excluded = if (annotated is KotlinType) excludedTypeAnnotationClasses else excludedAnnotationClasses

        val annotationsBuilder = StringBuilder {
            // Sort is needed just to fix some order when annotations resolved from modifiers
            // See AnnotationResolver.resolveAndAppendAnnotationsFromModifiers for clarification
            // This hack can be removed when modifiers will be resolved without annotations

            val sortedAnnotations = annotated.getAnnotations().getAllAnnotations()
            for ((annotation, target) in sortedAnnotations) {
                val annotationClass = annotation.getType().getConstructor().getDeclarationDescriptor() as ClassDescriptor

                if (!excluded.contains(DescriptorUtils.getFqNameSafe(annotationClass))) {
                    append(renderAnnotation(annotation, target)).append(" ")
                }
            }
        }

        builder.append(annotationsBuilder)
    }

    override fun renderAnnotation(annotation: AnnotationDescriptor, target: AnnotationUseSiteTarget?): String {
        return StringBuilder {
            append('@')
            if (target != null) {
                append(target.renderName + ":")
            }
            append(renderType(annotation.getType()))
            if (verbose) {
                renderAndSortAnnotationArguments(annotation).joinTo(this, ", ", "(", ")")
            }
        }.toString()
    }

    private fun renderAndSortAnnotationArguments(descriptor: AnnotationDescriptor): List<String> {
        val allValueArguments = descriptor.getAllValueArguments()
        val classDescriptor = if (renderDefaultAnnotationArguments) TypeUtils.getClassDescriptor(descriptor.getType()) else null
        val parameterDescriptorsWithDefaultValue = classDescriptor?.getUnsubstitutedPrimaryConstructor()?.getValueParameters()?.filter {
            it.declaresDefaultValue()
        } ?: emptyList()
        val defaultList = parameterDescriptorsWithDefaultValue.filter { !allValueArguments.containsKey(it) }.map {
            "${it.getName().asString()} = ..."
        }
        val argumentList = allValueArguments.entrySet()
                .map { entry ->
                    val name = entry.key.getName().asString()
                    val value = if (!parameterDescriptorsWithDefaultValue.contains(entry.key)) renderConstant(entry.value) else "..."
                    "$name = $value"
                }
        return (defaultList + argumentList).sorted()
    }

    private fun renderConstant(value: ConstantValue<*>): String {
        return when (value) {
            is ArrayValue -> value.value.map { renderConstant(it) }.joinToString(", ", "{", "}")
            is AnnotationValue -> renderAnnotation(value.value).removePrefix("@")
            is KClassValue -> renderType(value.value) + "::class"
            else -> value.toString()
        }
    }

    private fun renderVisibility(visibility: Visibility, builder: StringBuilder) {
        var visibility = visibility
        if (DescriptorRendererModifier.VISIBILITY !in modifiers) return
        if (normalizedVisibilities) {
            visibility = visibility.normalize()
        }
        if (!showInternalKeyword && visibility == Visibilities.DEFAULT_VISIBILITY) return
        builder.append(renderKeyword(visibility.displayName)).append(" ")
    }

    private fun renderModality(modality: Modality, builder: StringBuilder) {
        if (DescriptorRendererModifier.MODALITY !in modifiers) return
        val keyword = modality.name().toLowerCase()
        builder.append(renderKeyword(keyword)).append(" ")
    }

    private fun renderInner(isInner: Boolean, builder: StringBuilder) {
        if (DescriptorRendererModifier.INNER !in modifiers) return
        if (isInner) {
            builder.append(renderKeyword("inner")).append(" ")
        }
    }

    private fun renderData(isData: Boolean, builder: StringBuilder) {
        if (DescriptorRendererModifier.DATA !in modifiers || !isData) return
        builder.append(renderKeyword("data")).append(" ")
    }

    private fun renderModalityForCallable(callable: CallableMemberDescriptor, builder: StringBuilder) {
        if (!DescriptorUtils.isTopLevelDeclaration(callable) || callable.getModality() != Modality.FINAL) {
            if (overridesSomething(callable) && overrideRenderingPolicy == OverrideRenderingPolicy.RENDER_OVERRIDE && callable.getModality() == Modality.OPEN) {
                return
            }
            renderModality(callable.getModality(), builder)
        }
    }

    private fun renderOverride(callableMember: CallableMemberDescriptor, builder: StringBuilder) {
        if (DescriptorRendererModifier.OVERRIDE !in modifiers) return
        if (overridesSomething(callableMember)) {
            if (overrideRenderingPolicy != OverrideRenderingPolicy.RENDER_OPEN) {
                builder.append("override ")
                if (verbose) {
                    builder.append("/*").append(callableMember.getOverriddenDescriptors().size()).append("*/ ")
                }
            }
        }
    }

    private fun renderMemberKind(callableMember: CallableMemberDescriptor, builder: StringBuilder) {
        if (DescriptorRendererModifier.MEMBER_KIND !in modifiers) return
        if (verbose && callableMember.getKind() != CallableMemberDescriptor.Kind.DECLARATION) {
            builder.append("/*").append(callableMember.getKind().name().toLowerCase()).append("*/ ")
        }
    }

    private fun renderLateInit(propertyDescriptor: PropertyDescriptor, builder: StringBuilder) {
        if (propertyDescriptor.isLateInit) {
            builder.append("lateinit ")
        }
    }

    private fun renderAdditionalModifiers(functionDescriptor: FunctionDescriptor, builder: StringBuilder) {
        if (functionDescriptor.isOperator && functionDescriptor.overriddenDescriptors.none { it.isOperator }) {
            builder.append("operator ")
        }
        if (functionDescriptor.isInfix && functionDescriptor.overriddenDescriptors.none { it.isInfix }) {
            builder.append("infix ")
        }
        if (functionDescriptor.isExternal) {
            builder.append("external ")
        }
        if (functionDescriptor.isInline) {
            builder.append("inline ")
        }
        if (functionDescriptor.isTailrec) {
            builder.append("tailrec ")
        }
    }

    override fun render(declarationDescriptor: DeclarationDescriptor): String {
        return StringBuilder {
            declarationDescriptor.accept(RenderDeclarationDescriptorVisitor(), this)

            if (withDefinedIn) {
                appendDefinedIn(declarationDescriptor, this)
            }
        }.toString()
    }


    /* TYPE PARAMETERS */
    private fun renderTypeParameter(typeParameter: TypeParameterDescriptor, builder: StringBuilder, topLevel: Boolean) {
        if (topLevel) {
            builder.append(lt())
        }

        if (verbose) {
            builder.append("/*").append(typeParameter.getIndex()).append("*/ ")
        }

        if (typeParameter.isReified()) {
            builder.append(renderKeyword("reified")).append(" ")
        }
        val variance = typeParameter.getVariance().label
        if (!variance.isEmpty()) {
            builder.append(renderKeyword(variance)).append(" ")
        }
        renderName(typeParameter, builder)
        val upperBoundsCount = typeParameter.getUpperBounds().size()
        if ((upperBoundsCount > 1 && !topLevel) || upperBoundsCount == 1) {
            val upperBound = typeParameter.getUpperBounds().iterator().next()
            if (!KotlinBuiltIns.isDefaultBound(upperBound)) {
                builder.append(" : ").append(renderType(upperBound))
            }
        }
        else if (topLevel) {
            var first = true
            for (upperBound in typeParameter.getUpperBounds()) {
                if (KotlinBuiltIns.isDefaultBound(upperBound)) {
                    continue
                }
                if (first) {
                    builder.append(" : ")
                }
                else {
                    builder.append(" & ")
                }
                builder.append(renderType(upperBound))
                first = false
            }
        }
        else {
            // rendered with "where"
        }

        if (topLevel) {
            builder.append(gt())
        }
    }

    private fun renderTypeParameters(typeParameters: List<TypeParameterDescriptor>, builder: StringBuilder, withSpace: Boolean) {
        if (withoutTypeParameters) return

        if (!typeParameters.isEmpty()) {
            builder.append(lt())
            val iterator = typeParameters.iterator()
            while (iterator.hasNext()) {
                val typeParameterDescriptor = iterator.next()
                renderTypeParameter(typeParameterDescriptor, builder, false)
                if (iterator.hasNext()) {
                    builder.append(", ")
                }
            }
            builder.append(gt())
            if (withSpace) {
                builder.append(" ")
            }
        }
    }

    /* FUNCTIONS */
    private fun renderFunction(function: FunctionDescriptor, builder: StringBuilder) {
        if (!startFromName) {
            renderAnnotations(function, builder)
            renderVisibility(function.getVisibility(), builder)
            renderModalityForCallable(function, builder)
            renderAdditionalModifiers(function, builder)
            renderOverride(function, builder)
            renderMemberKind(function, builder)

            builder.append(renderKeyword("fun")).append(" ")
            renderTypeParameters(function.getTypeParameters(), builder, true)
            renderReceiver(function, builder)
        }

        renderName(function, builder)

        renderValueParameters(function.valueParameters, function.hasSynthesizedParameterNames(), builder)

        renderReceiverAfterName(function, builder)

        val returnType = function.getReturnType()
        if (!withoutReturnType && (unitReturnType || (returnType == null || !KotlinBuiltIns.isUnit(returnType)))) {
            builder.append(": ").append(if (returnType == null) "[NULL]" else escape(renderType(returnType)))
        }

        renderWhereSuffix(function.getTypeParameters(), builder)
    }

    private fun renderReceiverAfterName(callableDescriptor: CallableDescriptor, builder: StringBuilder) {
        if (!receiverAfterName) return

        val receiver = callableDescriptor.getExtensionReceiverParameter()
        if (receiver != null) {
            builder.append(" on ").append(escape(renderType(receiver.getType())))
        }
    }

    private fun renderReceiver(callableDescriptor: CallableDescriptor, builder: StringBuilder) {
        val receiver = callableDescriptor.getExtensionReceiverParameter()
        if (receiver != null) {
            val type = receiver.getType()
            var result = escape(renderType(type))
            if (shouldRenderAsPrettyFunctionType(type) && !TypeUtils.isNullableType(type)) {
                result = "($result)"
            }
            builder.append(result).append(".")
        }
    }

    private fun renderConstructor(constructor: ConstructorDescriptor, builder: StringBuilder) {
        renderAnnotations(constructor, builder)
        renderVisibility(constructor.getVisibility(), builder)
        renderMemberKind(constructor, builder)

        builder.append(renderKeyword("constructor"))
        if (secondaryConstructorsAsPrimary) {
            val classDescriptor = constructor.getContainingDeclaration()
            builder.append(" ")
            renderName(classDescriptor, builder)
            renderTypeParameters(classDescriptor.getTypeConstructor().getParameters(), builder, false)
        }

        renderValueParameters(constructor.valueParameters, constructor.hasSynthesizedParameterNames(), builder)

        if (secondaryConstructorsAsPrimary) {
            renderWhereSuffix(constructor.getTypeParameters(), builder)
        }
    }

    private fun renderWhereSuffix(typeParameters: List<TypeParameterDescriptor>, builder: StringBuilder) {
        if (withoutTypeParameters) return

        val upperBoundStrings = ArrayList<String>(0)

        for (typeParameter in typeParameters) {
            typeParameter.getUpperBounds()
                    .drop(1) // first parameter is rendered by renderTypeParameter
                    .mapTo(upperBoundStrings) { renderName(typeParameter.getName()) + " : " + escape(renderType(it)) }
        }

        if (!upperBoundStrings.isEmpty()) {
            builder.append(" ").append(renderKeyword("where")).append(" ")
            upperBoundStrings.joinTo(builder, ", ")
        }
    }

    override fun renderValueParameters(parameters: Collection<ValueParameterDescriptor>, synthesizedParameterNames: Boolean): String {
        return StringBuilder { renderValueParameters(parameters, synthesizedParameterNames, this) }.toString()
    }

    private fun renderValueParameters(parameters: Collection<ValueParameterDescriptor>, synthesizedParameterNames: Boolean, builder: StringBuilder) {
        val includeNames = shouldRenderParameterNames(synthesizedParameterNames)
        val parameterCount = parameters.size()
        valueParametersHandler.appendBeforeValueParameters(parameterCount, builder)
        for ((index, parameter) in parameters.withIndex()) {
            valueParametersHandler.appendBeforeValueParameter(parameter, index, parameterCount, builder)
            renderValueParameter(parameter, includeNames, builder, false)
            valueParametersHandler.appendAfterValueParameter(parameter, index, parameterCount, builder)
        }
        valueParametersHandler.appendAfterValueParameters(parameterCount, builder)
    }

    private fun shouldRenderParameterNames(synthesizedParameterNames: Boolean): Boolean {
        when (parameterNameRenderingPolicy) {
            ParameterNameRenderingPolicy.ALL -> return true
            ParameterNameRenderingPolicy.ONLY_NON_SYNTHESIZED -> return !synthesizedParameterNames
            ParameterNameRenderingPolicy.NONE -> return false
        }
    }

    /* VARIABLES */
    private fun renderValueParameter(valueParameter: ValueParameterDescriptor, includeName: Boolean, builder: StringBuilder, topLevel: Boolean) {
        if (topLevel) {
            builder.append(renderKeyword("value-parameter")).append(" ")
        }

        if (verbose) {
            builder.append("/*").append(valueParameter.getIndex()).append("*/ ")
        }

        renderAnnotations(valueParameter, builder)

        if (valueParameter.isCrossinline) {
            builder.append("crossinline ")
        }
        if (valueParameter.isNoinline) {
            builder.append("noinline ")
        }

        renderVariable(valueParameter, includeName, builder, topLevel)

        val withDefaultValue = renderDefaultValues && (if (debugMode) valueParameter.declaresDefaultValue() else valueParameter.hasDefaultValue())
        if (withDefaultValue) {
            builder.append(" = ...")
        }
    }

    private fun renderValVarPrefix(variable: VariableDescriptor, builder: StringBuilder) {
        builder.append(renderKeyword(if (variable.isVar()) "var" else "val")).append(" ")
    }

    private fun renderVariable(variable: VariableDescriptor, includeName: Boolean, builder: StringBuilder, topLevel: Boolean) {
        val realType = variable.getType()

        val varargElementType = (variable as? ValueParameterDescriptor)?.getVarargElementType()
        val typeToRender = varargElementType ?: realType

        if (varargElementType != null) {
            builder.append(renderKeyword("vararg")).append(" ")
        }
        if (topLevel && !startFromName) {
            renderValVarPrefix(variable, builder)
        }

        if (includeName) {
            renderName(variable, builder)
            builder.append(": ")
        }

        builder.append(escape(renderType(typeToRender)))

        renderInitializer(variable, builder)

        if (verbose && varargElementType != null) {
            builder.append(" /*").append(escape(renderType(realType))).append("*/")
        }
    }

    private fun renderProperty(property: PropertyDescriptor, builder: StringBuilder) {
        if (!startFromName) {
            renderAnnotations(property, builder)
            renderVisibility(property.getVisibility(), builder)

            if (property.isConst) {
                builder.append("const ")
            }

            renderModalityForCallable(property, builder)
            renderOverride(property, builder)
            renderLateInit(property, builder)
            renderMemberKind(property, builder)
            renderValVarPrefix(property, builder)
            renderTypeParameters(property.getTypeParameters(), builder, true)
            renderReceiver(property, builder)
        }

        renderName(property, builder)
        builder.append(": ").append(escape(renderType(property.getType())))

        renderReceiverAfterName(property, builder)

        renderInitializer(property, builder)

        renderWhereSuffix(property.getTypeParameters(), builder)
    }

    private fun renderInitializer(variable: VariableDescriptor, builder: StringBuilder) {
        if (includePropertyConstant) {
            variable.getCompileTimeInitializer()?.let { constant ->
                builder.append(" = ").append(escape(renderConstant(constant)))
            }
        }
    }

    /* CLASSES */
    private fun renderClass(klass: ClassDescriptor, builder: StringBuilder) {
        val isEnumEntry = klass.kind == ClassKind.ENUM_ENTRY

        if (!startFromName) {
            renderAnnotations(klass, builder)
            if (!isEnumEntry) {
                renderVisibility(klass.visibility, builder)
            }
            if (!(klass.kind == ClassKind.INTERFACE && klass.modality == Modality.ABSTRACT ||
                  klass.kind.isSingleton && klass.modality == Modality.FINAL)) {
                renderModality(klass.modality, builder)
            }
            renderInner(klass.isInner, builder)
            renderData(klass.isData, builder)
            renderClassKindPrefix(klass, builder)
        }

        if (!isCompanionObject(klass)) {
            if (!startFromName) renderSpaceIfNeeded(builder)
            renderName(klass, builder)
        }
        else {
            renderCompanionObjectName(klass, builder)
        }

        if (isEnumEntry) return

        val typeParameters = klass.typeConstructor.getParameters()
        renderTypeParameters(typeParameters, builder, false)

        if (!klass.kind.isSingleton && classWithPrimaryConstructor) {
            val primaryConstructor = klass.unsubstitutedPrimaryConstructor
            if (primaryConstructor != null) {
                builder.append(" ")
                renderAnnotations(primaryConstructor, builder, true)
                renderVisibility(primaryConstructor.visibility, builder)
                builder.append("constructor")
                renderValueParameters(primaryConstructor.valueParameters, primaryConstructor.hasSynthesizedParameterNames(), builder)
            }
        }

        renderSuperTypes(klass, builder)
        renderWhereSuffix(typeParameters, builder)
    }

    private fun renderSuperTypes(klass: ClassDescriptor, builder: StringBuilder) {
        if (withoutSuperTypes) return

        if (KotlinBuiltIns.isNothing(klass.defaultType)) return

        val supertypes = klass.getTypeConstructor().getSupertypes()
        if (supertypes.isEmpty() || supertypes.size() == 1 && KotlinBuiltIns.isAnyOrNullableAny(supertypes.iterator().next())) return

        renderSpaceIfNeeded(builder)
        builder.append(": ")
        supertypes.map { renderType(it) }
                .joinTo(builder, ", ")
    }

    private fun renderClassKindPrefix(klass: ClassDescriptor, builder: StringBuilder) {
        builder.append(renderKeyword(DescriptorRenderer.getClassKindPrefix(klass)))
    }


    /* OTHER */
    private fun renderModuleOrScript(moduleOrScript: DeclarationDescriptor, builder: StringBuilder) {
        renderName(moduleOrScript, builder)
    }

    private fun renderPackageView(packageView: PackageViewDescriptor, builder: StringBuilder) {
        builder.append(renderKeyword("package")).append(" ")
        builder.append(renderFqName(packageView.fqName.toUnsafe()))
        if (debugMode) {
            builder.append(" in context of ")
            renderName(packageView.module, builder)
        }
    }

    private fun renderPackageFragment(fragment: PackageFragmentDescriptor, builder: StringBuilder) {
        builder.append(renderKeyword("package-fragment")).append(" ")
        builder.append(renderFqName(fragment.fqName.toUnsafe()))
        if (debugMode) {
            builder.append(" in ")
            renderName(fragment.getContainingDeclaration(), builder)
        }
    }

    private fun renderAccessorModifiers(descriptor: PropertyAccessorDescriptor, builder: StringBuilder) {
        if (descriptor.isExternal) {
            builder.append("external ")
        }
    }

    /* STUPID DISPATCH-ONLY VISITOR */
    private inner class RenderDeclarationDescriptorVisitor : DeclarationDescriptorVisitor<Unit, StringBuilder> {
        override fun visitValueParameterDescriptor(descriptor: ValueParameterDescriptor, builder: StringBuilder) {
            renderValueParameter(descriptor, true, builder, true)
        }

        override fun visitVariableDescriptor(descriptor: VariableDescriptor, builder: StringBuilder) {
            renderVariable(descriptor, true, builder, true)
        }

        override fun visitPropertyDescriptor(descriptor: PropertyDescriptor, builder: StringBuilder) {
            renderProperty(descriptor, builder)
        }

        override fun visitPropertyGetterDescriptor(descriptor: PropertyGetterDescriptor, builder: StringBuilder) {
            if (renderAccessors) {
                renderAccessorModifiers(descriptor, builder)
                builder.append("getter for ")
                renderProperty(descriptor.getCorrespondingProperty(), builder)
            }
            else {
                visitFunctionDescriptor(descriptor, builder)
            }

        }

        override fun visitPropertySetterDescriptor(descriptor: PropertySetterDescriptor, builder: StringBuilder) {
            if (renderAccessors) {
                renderAccessorModifiers(descriptor, builder)
                builder.append("setter for ")
                renderProperty(descriptor.getCorrespondingProperty(), builder)
            }
            else {
                visitFunctionDescriptor(descriptor, builder)
            }
        }

        override fun visitFunctionDescriptor(descriptor: FunctionDescriptor, builder: StringBuilder) {
            renderFunction(descriptor, builder)
        }

        override fun visitReceiverParameterDescriptor(descriptor: ReceiverParameterDescriptor, data: StringBuilder) {
            throw UnsupportedOperationException("Don't render receiver parameters")
        }

        override fun visitConstructorDescriptor(constructorDescriptor: ConstructorDescriptor, builder: StringBuilder) {
            renderConstructor(constructorDescriptor, builder)
        }

        override fun visitTypeParameterDescriptor(descriptor: TypeParameterDescriptor, builder: StringBuilder) {
            renderTypeParameter(descriptor, builder, true)
        }

        override fun visitPackageFragmentDescriptor(descriptor: PackageFragmentDescriptor, builder: StringBuilder) {
            renderPackageFragment(descriptor, builder)
        }

        override fun visitPackageViewDescriptor(descriptor: PackageViewDescriptor, builder: StringBuilder) {
            renderPackageView(descriptor, builder)
        }

        override fun visitModuleDeclaration(descriptor: ModuleDescriptor, builder: StringBuilder) {
            renderModuleOrScript(descriptor, builder)
        }

        override fun visitScriptDescriptor(scriptDescriptor: ScriptDescriptor, builder: StringBuilder) {
            renderModuleOrScript(scriptDescriptor, builder)
        }

        override fun visitClassDescriptor(descriptor: ClassDescriptor, builder: StringBuilder) {
            renderClass(descriptor, builder)
        }
    }

    private fun renderSpaceIfNeeded(builder: StringBuilder) {
        val length = builder.length()
        if (length == 0 || builder.charAt(length - 1) != ' ') {
            builder.append(' ')
        }
    }

    private fun replacePrefixes(lowerRendered: String, lowerPrefix: String, upperRendered: String, upperPrefix: String, foldedPrefix: String): String? {
        if (lowerRendered.startsWith(lowerPrefix) && upperRendered.startsWith(upperPrefix)) {
            val lowerWithoutPrefix = lowerRendered.substring(lowerPrefix.length())
            val upperWithoutPrefix = upperRendered.substring(upperPrefix.length())
            val flexibleCollectionName = foldedPrefix + lowerWithoutPrefix

            if (lowerWithoutPrefix == upperWithoutPrefix) return flexibleCollectionName

            if (differsOnlyInNullability(lowerWithoutPrefix, upperWithoutPrefix)) {
                return flexibleCollectionName + "!"
            }
        }
        return null
    }

    private fun differsOnlyInNullability(lower: String, upper: String)
            = lower == upper.replace("?", "") || upper.endsWith("?") && ("$lower?") == upper || "($lower)?" == upper

    private fun overridesSomething(callable: CallableMemberDescriptor) = !callable.getOverriddenDescriptors().isEmpty()
}
