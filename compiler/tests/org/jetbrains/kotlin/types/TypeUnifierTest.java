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

package org.jetbrains.kotlin.types;

import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.annotations.Annotations;
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl;
import org.jetbrains.kotlin.descriptors.impl.TypeParameterDescriptorImpl;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtPsiFactoryKt;
import org.jetbrains.kotlin.psi.KtTypeProjection;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.resolve.TypeResolver;
import org.jetbrains.kotlin.resolve.scopes.KtScope;
import org.jetbrains.kotlin.resolve.scopes.RedeclarationHandler;
import org.jetbrains.kotlin.resolve.scopes.WritableScope;
import org.jetbrains.kotlin.resolve.scopes.WritableScopeImpl;
import org.jetbrains.kotlin.test.ConfigurationKind;
import org.jetbrains.kotlin.test.JetLiteFixture;
import org.jetbrains.kotlin.test.JetTestUtils;
import org.jetbrains.kotlin.tests.di.InjectionKt;

import java.util.Map;
import java.util.Set;

public class TypeUnifierTest extends JetLiteFixture {
    private Set<TypeConstructor> variables;

    private KotlinBuiltIns builtIns;
    private TypeResolver typeResolver;
    private TypeParameterDescriptor x;
    private TypeParameterDescriptor y;

    @Override
    protected KotlinCoreEnvironment createEnvironment() {
        return createEnvironmentWithMockJdk(ConfigurationKind.ALL);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();


        ModuleDescriptorImpl module = JetTestUtils.createEmptyModule();
        builtIns = module.getBuiltIns();
        typeResolver = InjectionKt.createContainerForTests(getProject(), module).getTypeResolver();
        x = createTypeVariable("X");
        y = createTypeVariable("Y");
        variables = Sets.newHashSet(x.getTypeConstructor(), y.getTypeConstructor());
    }

    private TypeParameterDescriptor createTypeVariable(String name) {
        return TypeParameterDescriptorImpl.createWithDefaultBound(
                builtIns.getBuiltInsModule(), Annotations.Companion.getEMPTY(), false, Variance.INVARIANT,
                Name.identifier(name), 0);
    }

    public void testNoVariables() throws Exception {
        doTest("Any", "Any", expect());
        doTest("Any", "Int", expect(false));
        doTest("Any?", "Any?", expect());
        doTest("Any?", "Any", expect(false));
        doTest("Any", "Any?", expect(false));
        doTest("List<Any>", "List<Any>", expect());
        doTest("List<Any>", "List<Int>", expect(false));
        doTest("List<out Any>", "List<out Any>", expect());
        doTest("List<out Any>", "List<in Any>", expect(false));
        doTest("List<out Any>", "List<Any>", expect(false));
        doTest("List<out Any>", "List<out Int>", expect(false));
        doTest("List<Any>", "Set<Any>", expect(false));
        doTest("List<List<Any>>", "List<Set<Any>>", expect(false));
        doTest("List<List<Any>>", "List<List<Any>>", expect());
    }

    public void testVariables() throws Exception {
        doTest("Any", "X", expect("X", "Any"));

        doTest("List<Any>", "List<X>", expect("X", "Any"));
        doTest("List<Any>", "Set<X>", expect(false));

        doTest("List<List<Any>>", "List<X>", expect("X", "List<Any>"));
        doTest("List<List<Any>>", "List<List<X>>", expect("X", "Any"));
        doTest("List<List<Any>>", "List<Set<X>>", expect(false));

        doTest("Map<Any, Any>", "Map<X, X>", expect("X", "Any"));
        doTest("Map<Any, String>", "Map<X, Y>", expect("X", "Any", "Y", "String"));
        doTest("Map<Any, String>", "Map<X, String>", expect("X", "Any"));
        doTest("Map<Any, Any>", "Map<X, String>", expect(false, "X", "Any"));

        doTest("X", "X", expect("X", "X"));
        doTest("List<X>", "List<X>", expect("X", "X"));
    }

    public void testDifferentValuesForOneVariable() throws Exception {
        doTest("Map<Any, String>", "Map<X, X>", expect(false));
    }

    public void testVariablesAndNulls() throws Exception {
        doTest("Any?", "X?", expect("X", "Any"));
        doTest("Any?", "X", expect("X", "Any?"));
        doTest("Any", "X?", expect(false));

        doTest("List<Any?>", "List<X?>", expect("X", "Any"));
        doTest("List<Any?>", "List<X>", expect("X", "Any?"));
        doTest("List<Any>", "List<X?>", expect(false));

        doTest("List<Any>?", "List<X>?", expect("X", "Any"));
        doTest("List<Any>", "List<X>?", expect(false));
        doTest("List<Any>?", "List<X>", expect(false));
    }

    public void testVariablesAndProjections() throws Exception {
        doTest("in Any", "X", expect("X", "in Any"));
        doTest("in Any", "in X", expect("X", "Any"));
        doTest("Any", "out X", expect(false));
        doTest("in Any", "out X", expect(false));

        doTest("List<in Any>", "List<X>", expect("X", "in Any"));
        doTest("List<out Any>", "List<X>", expect("X", "out Any"));
        doTest("List<out Any>", "List<out X>", expect("X", "Any"));
        doTest("List<out Any>", "List<in X>", expect(false));
        doTest("List<Any>", "List<in X>", expect(false));
    }

    public void testVariablesNullsAndProjections() throws Exception {
        doTest("in Any?", "X", expect("X", "in Any?"));
        doTest("in Any", "X?", expect(false));
    }

    public void testIllFormedTypes() throws Exception {
        doTest("List", "List<X>", expect(false));
        doTest("Map<String>", "Map<X, Y>", expect(false));
    }

    private static Map<String, String> expect(String... strs) {
        return expect(true, strs);
    }

    private static Map<String, String> expect(boolean success, String... strs) {
        Map<String, String> map = Maps.newHashMap();
        putResult(map, success);
        for (int i = 0; i < strs.length; i += 2) {
            String key = strs[i];
            String value = strs[i + 1];
            map.put(key, value);
        }
        return map;
    }

    private void doTest(String known, String withVariables, @NotNull Map<String, String> expected) {
        TypeUnifier.UnificationResult map = TypeUnifier.unify(
                makeType(known),
                makeType(withVariables),
                new Predicate<TypeConstructor>() {
                    @Override
                    public boolean apply(TypeConstructor tc) {
                        return variables.contains(tc);
                    }
                }
        );
        assertEquals(expected, toStrings(map));
    }

    @Nullable
    private static Map<String, String> toStrings(TypeUnifier.UnificationResult map) {
        Map<String, String> result = Maps.newHashMap();
        putResult(result, map.isSuccess());
        for (Map.Entry<TypeConstructor, TypeProjection> entry : map.getSubstitution().entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return result;
    }

    private static void putResult(Map<String, String> result, boolean success) {
        result.put("_RESULT_", String.valueOf(success));
    }

    private TypeProjection makeType(String typeStr) {
        return makeTypeProjection(builtIns.getBuiltInsPackageScope(), typeStr);
    }

    private TypeProjection makeTypeProjection(KtScope scope, String typeStr) {
        WritableScopeImpl withX =
                new WritableScopeImpl(scope, scope.getContainingDeclaration(), RedeclarationHandler.DO_NOTHING, "With X");
        withX.addClassifierDescriptor(x);
        withX.addClassifierDescriptor(y);
        withX.changeLockLevel(WritableScope.LockLevel.READING);

        KtTypeProjection projection = KtPsiFactoryKt
                .KtPsiFactory(getProject()).createTypeArguments("<" + typeStr + ">").getArguments().get(0);

        KtTypeReference typeReference = projection.getTypeReference();
        assert typeReference != null;
        KotlinType type = typeResolver.resolveType(TypeTestUtilsKt.asLexicalScope(withX), typeReference, JetTestUtils.DUMMY_TRACE, true);

        return new TypeProjectionImpl(getProjectionKind(typeStr, projection), type);
    }

    private static Variance getProjectionKind(String typeStr, KtTypeProjection projection) {
        Variance variance;
        switch (projection.getProjectionKind()) {
            case IN:
                variance = Variance.IN_VARIANCE;
                break;
            case OUT:
                variance = Variance.OUT_VARIANCE;
                break;
            case NONE:
                variance = Variance.INVARIANT;
                break;
            default:
                throw new UnsupportedOperationException("Star projections are not supported: " + typeStr);
        }
        return variance;
    }
}
