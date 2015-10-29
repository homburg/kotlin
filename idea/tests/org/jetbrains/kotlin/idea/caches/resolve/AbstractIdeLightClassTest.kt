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

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.impl.compiled.ClsModifierListImpl
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.asJava.KotlinLightField
import org.jetbrains.kotlin.asJava.KotlinLightMethod
import org.jetbrains.kotlin.asJava.LightClassTestCommon
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.test.JetLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.JetWithJdkAndRuntimeLightProjectDescriptor
import java.io.File

abstract class AbstractIdeLightClassTest : JetLightCodeInsightFixtureTestCase() {

    fun doTest(testDataPath: String) {
        myFixture.configureByFile(testDataPath)

        val project = project
        LightClassTestCommon.testLightClass(
                File(testDataPath),
                findLightClass = {
                    val clazz = JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project))
                    if (clazz != null) {
                        checkPsiElementStructure(clazz)
                    }
                    clazz

                },
                normalizeText = {
                    //NOTE: ide and compiler differ in names generated for parameters with unspecified names
                    it.replace("java.lang.String s,", "java.lang.String p,").replace("java.lang.String s)", "java.lang.String p)")
                            .replace("java.lang.String s1", "java.lang.String p1").replace("java.lang.String s2", "java.lang.String p2")
                }
        )
    }

    override fun getProjectDescriptor() = JetWithJdkAndRuntimeLightProjectDescriptor.INSTANCE

    val TEST_DATA_KEY = Key.create<Int>("Test Key")

    private fun checkPsiElementStructure(lightClass: PsiClass) {
        checkPsiElement(lightClass)

        val typeParameterList = lightClass.typeParameterList
        if (typeParameterList != null) {
            checkPsiElement(typeParameterList)
            typeParameterList.typeParameters.forEach { checkPsiElement(it) }
        }

        lightClass.innerClasses.forEach { checkPsiElementStructure(it) }

        lightClass.methods.forEach {
            it.parameterList.parameters.forEach { checkPsiElement(it) }
            checkPsiElement(it)
        }

        lightClass.fields.forEach { checkPsiElement(it) }
    }

    private fun checkPsiElement(element: PsiModifierListOwner) {
        val modifierList = element.modifierList
        if (modifierList != null) {
            if (element is KotlinLightField<*, *> || element is KotlinLightMethod) {
                assert(modifierList is ClsModifierListImpl)
            }
            else {
                checkPsiElement(modifierList)
            }
        }

        checkPsiElement(element as PsiElement)
    }

    private fun checkPsiElement(element: PsiElement) {
        with(element) {
            try {
                getProject()
                assert(getLanguage() == KotlinLanguage.INSTANCE)
                getManager()
                getChildren()
                getParent()
                getFirstChild()
                getLastChild()
                getNextSibling()
                getPrevSibling()
                getContainingFile()
                getTextRange()
                getStartOffsetInParent()
                getTextLength()
                findElementAt(0)
                findReferenceAt(0)
                getTextOffset()
                getText()
                textToCharArray()
                getNavigationElement()
                getOriginalElement()
                textMatches("")
                assert(textMatches(this))
                textContains('a')
                accept(PsiElementVisitor.EMPTY_VISITOR)
                acceptChildren(PsiElementVisitor.EMPTY_VISITOR)

                val copy = copy()
                assert(copy == null || copy.javaClass == this.javaClass)

                // Modify methods:
                // add(this)
                // addBefore(this, lastChild)
                // addAfter(firstChild, this)
                // checkAdd(this)
                // addRange(firstChild, lastChild)
                // addRangeBefore(firstChild, lastChild, lastChild)
                // addRangeAfter(firstChild, lastChild, firstChild)
                // delete()
                // checkDelete()
                // deleteChildRange(firstChild, lastChild)
                // replace(this)

                assert(isValid())
                isWritable()
                getReference()
                getReferences()
                putCopyableUserData(TEST_DATA_KEY, 12)

                assert(getCopyableUserData(TEST_DATA_KEY) == 12)
                // assert(copy().getCopyableUserData(TEST_DATA_KEY) == 12) { this } Doesn't work

                // processDeclarations(...)

                getContext()
                isPhysical()
                getResolveScope()
                getUseScope()
                getNode()
                toString()
                assert(isEquivalentTo(this))
            }
            catch (t: Throwable) {
                throw AssertionErrorWithCause("Failed for ${this.javaClass} ${this}", t)
            }
        }

    }
}