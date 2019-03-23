package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalAnalysis.loops;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.PhpFile;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiEquivalenceUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiPsiSearchUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Stream;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class SuspiciousLoopInspector extends BasePhpInspection {
    private static final String messageMultipleConditions = "Please use && or || for multiple conditions. Currently no checks are performed after first positive result.";
    private static final String patternOverridesLoopVars  = "Variable '$%v%' is introduced in a outer loop and overridden here.";
    private static final String patternOverridesParameter = "Variable '$%v%' is introduced as a %t% parameter and overridden here.";
    private static final String patternConditionAnomaly   = "A parent condition '%s' looks suspicious.";

    private static final Set<IElementType> operationsAnomaly = new HashSet<>();
    static {
        operationsAnomaly.add(PhpTokenTypes.opLESS);
        operationsAnomaly.add(PhpTokenTypes.opLESS_OR_EQUAL);
        operationsAnomaly.add(PhpTokenTypes.opEQUAL);
        operationsAnomaly.add(PhpTokenTypes.opIDENTICAL);
    }

    @NotNull
    public String getShortName() {
        return "SuspiciousLoopInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new BasePhpElementVisitor() {
            @Override
            public void visitPhpForeach(@NotNull ForeachStatement statement) {
                if (this.isContainingFileSkipped(statement)) { return; }

                this.inspectVariables(statement);
                this.inspectParentConditions(statement);
            }

            @Override
            public void visitPhpFor(@NotNull For statement) {
                if (this.isContainingFileSkipped(statement)) { return; }

                this.inspectConditions(statement);
                this.inspectVariables(statement);
            }

            private void inspectParentConditions(@NotNull ForeachStatement statement) {
                final PsiElement source = statement.getArray();
                if (source instanceof Variable || source instanceof FieldReference) {
                    PsiElement parent = statement.getParent();
                    while (parent != null && !(parent instanceof Function) && !(parent instanceof PsiFile)) {
                        /* extract condition */
                        PsiElement condition = null;
                        if (parent instanceof If) {
                            condition = ((If) parent).getCondition();
                        } else if (parent instanceof ElseIf) {
                            condition = ((ElseIf) parent).getCondition();
                            parent    = parent.getParent(); /* skip if processing */
                        } else if (parent instanceof Else) {
                            parent    = parent.getParent(); /* skip if processing */
                        }
                        /* process condition and continue */
                        if (condition != null) {
                            final PsiElement anomaly = this.findFirstConditionAnomaly(source, condition);
                            if (anomaly != null && !this.isOverridden(source, condition.getParent())) {
                                holder.registerProblem(
                                        statement.getFirstChild(),
                                        String.format(patternConditionAnomaly, anomaly.getText())
                                );

                                break;
                            }
                        }
                        parent = parent.getParent();
                    }
                }
            }

            private boolean isOverridden(@NotNull PsiElement source, @NotNull PsiElement branch) {
                boolean result = false;
                for (final PsiElement child: PsiTreeUtil.findChildrenOfType(branch, source.getClass())) {
                    if (child == source) {
                        break;
                    }
                    final PsiElement parent = child.getParent();
                    if (OpenapiTypesUtil.isAssignment(parent) && OpenapiEquivalenceUtil.areEqual(source, child)) {
                        final AssignmentExpression assignment = (AssignmentExpression) parent;
                        if (result = assignment.getValue() != child) {
                            break;
                        }
                    }
                }
                return result;
            }

            private PsiElement findFirstConditionAnomaly(@NotNull PsiElement source, @NotNull PsiElement condition) {
                for (final PsiElement expression : OpenapiPsiSearchUtil.findEqual(condition, source)) {
                    final PsiElement parent        = expression.getParent();
                    final PsiElement directContext = parent instanceof ParameterList ? parent.getParent() : parent;
                    final PsiElement outerContext  = directContext.getParent();

                    /* case: empty statement */
                    if (directContext instanceof PhpEmpty) {
                        if (outerContext instanceof UnaryExpression) {
                            final UnaryExpression unary = (UnaryExpression) outerContext;
                            if (OpenapiTypesUtil.is(unary.getOperation(), PhpTokenTypes.opNOT)) {
                                return null;
                            }
                        } else if (outerContext instanceof BinaryExpression) {
                            final IElementType operation = ((BinaryExpression) outerContext).getOperationType();
                            /* skip analyzing comparisons - I don't want to invest my time into this right now*/
                            if (OpenapiTypesUtil.tsCOMPARE_EQUALITY_OPS.contains(operation)) {
                                return null;
                            }
                        }
                        return directContext;
                    }

                    /* case: count function/method */
                    if (outerContext instanceof BinaryExpression && directContext instanceof FunctionReference) {
                        final FunctionReference call = (FunctionReference) directContext;
                        final String functionName    = call.getName();
                        if (functionName != null && functionName.equals("count")) {
                            final BinaryExpression binary = (BinaryExpression) outerContext;
                            if (call == binary.getLeftOperand()) {
                                final PsiElement threshold = binary.getRightOperand();
                                if (OpenapiTypesUtil.isNumber(threshold)) {
                                    final String number   = threshold.getText();
                                    final IElementType op = binary.getOperationType();
                                    if (op == PhpTokenTypes.opLESS && number.equals("2")) {
                                        return outerContext;
                                    }
                                    if (operationsAnomaly.contains(op) && (number.equals("0") || number.equals("1"))) {
                                        return outerContext;
                                    }
                                }
                            }
                        }
                    }
                }
                return null;
            }

            private void inspectConditions(@NotNull For forStatement) {
                if (forStatement.getConditionalExpressions().length > 1) {
                    holder.registerProblem(forStatement.getFirstChild(), messageMultipleConditions);
                }
            }

            private void inspectVariables(@NotNull PhpPsiElement loop) {
                final Set<String> loopVariables = this.getLoopVariables(loop);

                final Function function = ExpressionSemanticUtil.getScope(loop);
                if (null != function) {
                    final HashSet<String> parameters = new HashSet<>();
                    for (final Parameter param : function.getParameters()) {
                        parameters.add(param.getName());
                    }

                    loopVariables.forEach(variable -> {
                        if (parameters.contains(variable)) {
                            final String message = patternOverridesParameter
                                .replace("%v%", variable)
                                .replace("%t%", function instanceof Method ? "method" : "function");
                            holder.registerProblem(loop.getFirstChild(), message);
                        }
                    });
                    parameters.clear();
                }

                /* scan parents until reached file/callable */
                PsiElement parent = loop.getParent();
                while (null != parent && ! (parent instanceof Function) && ! (parent instanceof PhpFile)) {
                    /* inspect parent loops for conflicted variables */
                    if (parent instanceof For || parent instanceof ForeachStatement) {
                        final Set<String> parentVariables = this.getLoopVariables((PhpPsiElement) parent);
                        loopVariables.forEach(variable -> {
                            if (parentVariables.contains(variable)) {
                                final String message = patternOverridesLoopVars.replace("%v%", variable);
                                holder.registerProblem(loop.getFirstChild(), message);
                            }
                        });
                        parentVariables.clear();
                    }

                    parent = parent.getParent();
                }
                loopVariables.clear();
            }

            @NotNull
            private Set<String> getLoopVariables(@NotNull PhpPsiElement loop) {
                final Set<String> variables = new HashSet<>();
                if (loop instanceof For) {
                    /* get variables from assignments */
                    Stream.of(((For) loop).getInitialExpressions()).forEach(init -> {
                        if (init instanceof AssignmentExpression) {
                            final PhpPsiElement variable = ((AssignmentExpression) init).getVariable();
                            if (variable instanceof Variable) {
                                final String variableName = variable.getName();
                                if (variableName != null) {
                                    variables.add(variableName);
                                }
                            }
                        }
                    });
                } else if (loop instanceof ForeachStatement) {
                    ((ForeachStatement) loop).getVariables().forEach(variable -> variables.add(variable.getName()));
                }

                return variables;
            }
        };
    }
}