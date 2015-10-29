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

package org.jetbrains.kotlin.idea.debugger.evaluate

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.kotlin.checkers.AbstractJetPsiCheckerTest
import org.jetbrains.kotlin.idea.caches.resolve.analyzeFully
import org.jetbrains.kotlin.idea.caches.resolve.resolveImportReference
import org.jetbrains.kotlin.idea.completion.test.AbstractJvmBasicCompletionTest
import org.jetbrains.kotlin.idea.completion.test.ExpectedCompletionUtils
import org.jetbrains.kotlin.idea.completion.test.handlers.AbstractCompletionHandlerTest
import org.jetbrains.kotlin.idea.test.JetWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.idea.util.ImportInsertHelper
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.test.InTextDirectivesUtils
import org.jetbrains.kotlin.test.JetTestUtils
import java.io.File
import kotlin.test.assertNull

public abstract class AbstractCodeFragmentHighlightingTest : AbstractJetPsiCheckerTest() {
    override fun doTest(filePath: String) {
        myFixture.configureByCodeFragment(filePath)
        myFixture.checkHighlighting(true, false, false)
    }

    fun doTestWithImport(filePath: String) {
        myFixture.configureByCodeFragment(filePath)

        runWriteAction {
            val fileText = FileUtil.loadFile(File(filePath), true)
            val file = myFixture.getFile() as KtFile
            InTextDirectivesUtils.findListWithPrefixes(fileText, "// IMPORT: ").forEach {
                val descriptor = file.resolveImportReference(FqName(it)).singleOrNull()
                                            ?: error("Could not resolve descriptor to import: $it")
                ImportInsertHelper.getInstance(getProject()).importDescriptor(file, descriptor)
            }
        }

        myFixture.checkHighlighting(true, false, false)
    }
}

public abstract class AbstractCodeFragmentCompletionTest : AbstractJvmBasicCompletionTest() {
    override fun setUpFixture(testPath: String) {
        myFixture.configureByCodeFragment(testPath)
    }
}

public abstract class AbstractCodeFragmentCompletionHandlerTest : AbstractCompletionHandlerTest(CompletionType.BASIC) {
    override fun setUpFixture(testPath: String) {
        myFixture.configureByCodeFragment(testPath)
    }

    override fun doTest(testPath: String) {
        super.doTest(testPath)

        val fragment = myFixture.getFile() as KtCodeFragment
        fragment.checkImports(testPath)
    }
}

public abstract class AbstractCodeFragmentAutoImportTest : AbstractJetPsiCheckerTest() {
    override fun doTest(filePath: String) {
        myFixture.configureByCodeFragment(filePath)
        myFixture.doHighlighting()

        val importFix = myFixture.availableIntentions.singleOrNull { it.familyName == "Import" }
                        ?: error("No import fix available")
        importFix.invoke(project, editor, file)

        myFixture.checkResultByFile(filePath + ".after")

        val fragment = myFixture.file as KtCodeFragment
        fragment.checkImports(testDataPath + File.separator + filePath)

        val fixAfter = myFixture.availableIntentions.firstOrNull { it.familyName == "Import" }
        assertNull(fixAfter, "No import fix should be available after")
    }

    override fun getProjectDescriptor() = JetWithJdkAndRuntimeLightProjectDescriptor.INSTANCE
    override fun getTestDataPath() = JetTestUtils.getHomeDirectory()
}

private fun KtCodeFragment.checkImports(testPath: String) {
    val importList = importsAsImportList()
    val importsText = StringUtil.convertLineSeparators(importList?.text ?: "")
    val fragmentAfterFile = File(testPath + ".after.imports")

    if (fragmentAfterFile.exists()) {
        JetTestUtils.assertEqualsToFile(fragmentAfterFile, importsText)
    }
    else {
        assertNull(importsText.isEmpty(), "Unexpected imports found: $importsText" )
    }
}

private fun JavaCodeInsightTestFixture.configureByCodeFragment(filePath: String) {
    configureByFile(filePath)

    val elementAt = getFile()?.findElementAt(getCaretOffset())
    val file = createCodeFragment(filePath, elementAt!!)

    val typeStr = InTextDirectivesUtils.findStringWithPrefixes(getFile().getText(), "// ${ExpectedCompletionUtils.RUNTIME_TYPE} ")
    if (typeStr != null) {
        file.putCopyableUserData(KtCodeFragment.RUNTIME_TYPE_EVALUATOR, {
            val codeFragment = KtPsiFactory(getProject()).createBlockCodeFragment("val xxx: $typeStr" , PsiTreeUtil.getParentOfType(elementAt, javaClass<KtElement>()))
            val context = codeFragment.analyzeFully()
            val typeReference: KtTypeReference = PsiTreeUtil.getChildOfType(codeFragment.getContentElement().getFirstChild(), javaClass())!!
            context[BindingContext.TYPE, typeReference]
        })
    }

    configureFromExistingVirtualFile(file.getVirtualFile()!!)
}

private fun createCodeFragment(filePath: String, contextElement: PsiElement): KtCodeFragment {
    val fileForFragment = File(filePath + ".fragment")
    val codeFragmentText = FileUtil.loadFile(fileForFragment, true).trim()
    val psiFactory = KtPsiFactory(contextElement.getProject())
    if (fileForFragment.readLines().size() == 1) {
        return psiFactory.createExpressionCodeFragment(
                codeFragmentText,
                KotlinCodeFragmentFactory.getContextElement(contextElement)
        )
    }
    return psiFactory.createBlockCodeFragment(
            codeFragmentText,
            KotlinCodeFragmentFactory.getContextElement(contextElement)
    )
}
