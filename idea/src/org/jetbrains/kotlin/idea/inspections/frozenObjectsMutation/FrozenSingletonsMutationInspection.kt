/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections.frozenObjectsMutation

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.idea.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember

class FrozenSingletonsMutationInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return SingletoneVisitor(holder)
    }
}

private class SingletoneVisitor(var holder: ProblemsHolder) : KtVisitorVoid() {

    companion object {
        private const val THREADLOCAL_ANNOTATION = "@kotlin.native.ThreadLocal"
        private val BINARY_MUTATION_OPERATORS = TokenSet.create(
            KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ,
            KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ
        )
        private val POSTFIX_MUTATION_OPERATORS = TokenSet.create(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

    }

    val expressionVisitor =
        ExpressionVisitor(holder)


    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        if (!checkIfObject(classOrObject)) return
        if (!classOrObject.isTopLevel()) return
        if (checkThreadlocalAnnotation(classOrObject)) return

        classOrObject.findDescendantOfType<KtClassBody>()?.children?.forEach {
            it.accept(object : KtVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    visitProperty(property, holder)
                }
            })
        }
    }

    private fun checkThreadlocalAnnotation(classOrObject: KtClassOrObject) : Boolean {
        return classOrObject.annotations.find { it.name.equals(THREADLOCAL_ANNOTATION) } != null
    }

    private fun checkIfObject(classOrObject: KtClassOrObject) : Boolean {
        if (classOrObject is KtObjectDeclaration) return true
        return false
    }

    private fun visitProperty(property: KtProperty, holder: ProblemsHolder) {
        val usages = ReferencesSearch.search(property).findAll()
        usages.forEach {
            (it.element.parent as? KtExpression)?.apply {
                this.accept(expressionVisitor)
            }
        }
    }

    private class ExpressionVisitor(var holder: ProblemsHolder) :KtVisitorVoid() {

        private fun findReceiverParent(expression: KtExpression) : KtExpression {
            expression.children.forEach {
                val dot = it as? KtDotQualifiedExpression
                val safe = it as? KtSafeQualifiedExpression
                if (dot != null) return findReceiverParent(dot)
                if (safe != null) return findReceiverParent(safe)
            }
            return expression
        }

        private fun findReturn(expression: KtCallExpression?) : KtNameReferenceExpression? {

            fun resolveLambda(lambdaExpression: KtLambdaExpression) : KtNameReferenceExpression? {
                val block = lambdaExpression.findDescendantOfType<KtBlockExpression>()
                var lastRef : KtReferenceExpression? = null
                block?.children?.forEach {
                    lastRef = it as? KtReferenceExpression
                }
                when(lastRef) {
                    is KtCallExpression -> return findReturn(lastRef as KtCallExpression)
                    is KtNameReferenceExpression -> return lastRef as KtNameReferenceExpression
                    else -> return null
                }
            }

            val lambdaRef = expression?.findDescendantOfType<KtLambdaExpression>()

            if (lambdaRef != null) {
                return resolveLambda(lambdaRef)
            }
            val callRef = expression?.findDescendantOfType<KtNameReferenceExpression>()
            val returnRef = extractElementReference(callRef?.references)
                ?.resolve()
                ?.findDescendantOfType<KtBlockExpression>()
                ?.findDescendantOfType<KtReturnExpression>()
            val ref = returnRef?.findDescendantOfType<KtNameReferenceExpression>()
            val call = returnRef?.findDescendantOfType<KtCallExpression>()
            if (ref != null) {
                return ref
            } else if (call != null){
                findReturn(call)
            }
            return null
        }

        private fun isReceiverLocal(expression: KtExpression) : Boolean? {
            val receiverParent = findReceiverParent(expression)
            val callExpression = receiverParent.findDescendantOfType<KtCallExpression>()
            if (callExpression != null) {
                return checkReferenceLocal(findReturn(callExpression))
            } else {
                val references = receiverParent.findDescendantOfType<KtReferenceExpression>()?.references
                val ref = extractElementReference(references)
                val resolution = ref?.resolve()
                return checkReferenceLocal(receiverParent.findDescendantOfType<KtReferenceExpression>())
            }
        }

        private fun extractElementReference(references: Array<out PsiReference>?) : PsiReference? {
            return references?.find {
                it is KtSimpleNameReference
            }
        }

        private fun checkReferenceLocal(expression: KtExpression?) : Boolean? {
            return when(val result = (extractElementReference(expression?.references)
                ?.resolve())) {
                is KtProperty -> result.isLocal
                is KtObjectDeclaration -> false
                else -> null
            }
        }

        private fun ifInitializerChild(expression: KtExpression) : Boolean {
            var parent = expression.parent
            while (!parent.isTopLevelKtOrJavaMember()) {
                if (parent is KtClassInitializer) return true
                parent = parent.parent
            }
            return false
        }

        private fun isTopParentDotExpression(expression: KtExpression) : Boolean {
            return expression.parent !is KtOperationExpression && expression.parent !is KtQualifiedExpression
        }

        private fun visitDotOrSafeQualifiedExpression(expression: KtExpression) {
            if (isTopParentDotExpression(expression)) {
                /*val localreceiver = isReceiverLocal(expression) ?: return
                if (localreceiver) return*/
                extractElementReference(expression.findDescendantOfType<KtCallExpression>()
                    ?.findDescendantOfType<KtNameReferenceExpression>()
                    ?.references)
                    ?.resolve()
                    ?.findDescendantOfType<KtBlockExpression>()?.apply {
                        this.accept(this@ExpressionVisitor)
                    }
            } else {
                expression.parent.accept(this)
            }
        }

        override fun visitBlockExpression(expression: KtBlockExpression) {
            if (ifInitializerChild(expression)) return
            expression.children.forEach {
                it.accept(this)
            }
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
            if (expression.operationToken !in BINARY_MUTATION_OPERATORS) return
            /*val localreceiver = isReceiverLocal(expression) ?: return
            if (localreceiver) return*/
            if (ifInitializerChild(expression)) return
            if (expression.left is KtReferenceExpression) {
                //determine whether property is mutated
                /*val localProp = checkReferenceLocal(expression.left) ?: return
                if (localProp) return*/
                val problemDescriptor = holder.manager.createProblemDescriptor(
                    expression,
                    "Frozen object mutation",
                    true,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    true)
                holder.registerProblem(problemDescriptor)

            } else {
                /*val localProp = checkReferenceLocal(expression.findDescendantOfType<KtNameReferenceExpression>()) ?: return
                if (localProp) return*/
                val problemDescriptor = holder.manager.createProblemDescriptor(
                    expression,
                    "Frozen object mutation",
                    true,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    true)
                holder.registerProblem(problemDescriptor)

            }
        }

        override fun visitPostfixExpression(expression: KtPostfixExpression) {
            if (expression.operationToken !in POSTFIX_MUTATION_OPERATORS) return
            /*val localreceiver = isReceiverLocal(expression) ?: return
            if (localreceiver) return*/
            if (ifInitializerChild(expression)) return
            val problemDescriptor = holder.manager.createProblemDescriptor(
                expression,
                "Frozen object mutation",
                true,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                true)
            holder.registerProblem(problemDescriptor)
        }

        override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
            visitDotOrSafeQualifiedExpression(expression)
        }

        override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression) {
            visitDotOrSafeQualifiedExpression(expression)
        }

        override fun visitCallExpression(expression: KtCallExpression) {
            if (!isTopParentDotExpression(expression)) return
            expression.findDescendantOfType<KtBlockExpression>()?.accept(this)
        }

    }
}