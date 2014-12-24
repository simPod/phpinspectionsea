package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalAnalysis;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedList;

public class ForeachSourceInspector extends BasePhpInspection {
    private static final String strAlreadyHandled = "\\already-handled";

    private static final String strProblemResolvingMixed = "Could not resolve this source type, specify possible types instead of mixed";
    private static final String strProblemResolvingArrayItemType = "Could not resolve this source type, array item type annotation needed";
    private static final String strProblemResolvingClassSlotType = "Could not resolve this source type, method or property type annotation needed";
    private static final String strProblemResolvingParameterType = "Could not resolve this source type, parameter type annotation needed";
    private static final String strProblemResolvingClassSlot = "Could not resolve this source type, expression  type annotation needed";
    private static final String strProblemDescription = "This source type can not be iterated: given ";

    @NotNull
    public String getDisplayName() {
        return "Semantics: foreach source";
    }

    @NotNull
    public String getShortName() {
        return "ForeachSourceInspection";
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new BasePhpElementVisitor() {
            public void visitPhpForeach(ForeachStatement foreach) {
                this.inspectSource(foreach.getArray());
            }

            private void inspectSource(PsiElement objSource) {
                objSource = ExpressionSemanticUtil.getExpressionTroughParenthesis(objSource);
                if (null == objSource) {
                    return;
                }

                boolean isExpressionInspected = false;
                LinkedList<String> listSignatureTypes = new LinkedList<>();
                if (objSource instanceof Variable) {
                    this.lookupType(((Variable) objSource).getSignature(), objSource, listSignatureTypes);
                    isExpressionInspected = true;
                }
                if (objSource instanceof FieldReference) {
                    this.lookupType(((FieldReference) objSource).getSignature(), objSource, listSignatureTypes);
                    isExpressionInspected = true;
                }
                if (objSource instanceof MethodReference) {
                    this.lookupType(((MethodReference) objSource).getSignature(), objSource, listSignatureTypes);
                    isExpressionInspected = true;
                }
                if (objSource instanceof FunctionReference && !(objSource instanceof MethodReference)) {
                    this.lookupType(((FunctionReference) objSource).getSignature(), objSource, listSignatureTypes);
                    isExpressionInspected = true;
                }

                if (!isExpressionInspected) {
                    /** TODO: check why, log warning */
                    return;
                }

                this.analyseTypesProvided(objSource, listSignatureTypes);
                final boolean isProcessed = this.analyseTypesProvided(objSource, listSignatureTypes);

                if (!isProcessed) {
                    /** debug warnings */
                    String strTypes = "";
                    String strGlue = "";
                    for (String strType : listSignatureTypes) {
                        strTypes += strGlue + strType;
                        strGlue = "|";
                    }

                    holder.registerProblem(objSource, "types extracted: " + strTypes, ProblemHighlightType.WEAK_WARNING);
                }
                listSignatureTypes.clear();
            }


            private boolean analyseTypesProvided(PsiElement objTargetExpression, LinkedList<String> listSignatureTypes) {
                if (listSignatureTypes.size() == 0) {
                    return false;
                }

                if (listSignatureTypes.size() == 1) {
                    String strType = listSignatureTypes.get(0);
                    if (strType.equals("\\array")) {
                        return true;
                    }

                    if (strType.equals("\\string") || strType.equals("\\null") || strType.equals("\\bool")) {
                        holder.registerProblem(objTargetExpression, strProblemDescription + strType, ProblemHighlightType.ERROR);
                        return true;
                    }

                    if (strType.equals("\\mixed")) {
                        holder.registerProblem(objTargetExpression, strProblemResolvingMixed, ProblemHighlightType.WEAK_WARNING);
                        return true;
                    }

                    if (strType.equals("\\class-not-resolved") || strType .equals(strAlreadyHandled)) {
                        return true;
                    }
                }

                /** poly - variant analysis here*/
                return false;
            }

            private void lookupType (String strSignature, PsiElement objTargetExpression, LinkedList<String> listSignatureTypes) {
                /** re-dispatch or terminate lookup */
                if (null == strSignature || strSignature.equals("")) {
                    return;
                }
                if (strSignature.contains("|")) {
                    for (String strSignaturePart : strSignature.split("\\|")) {
                        this.lookupType(strSignaturePart, objTargetExpression, listSignatureTypes);
                    }
                    return;
                }

                /** === early returns=== **/
                /** skip looking up into variable assignment / function */
                if (strSignature.startsWith("#V") || strSignature.startsWith("#F")) {
                    /** TODO: lookup assignments for types extraction, un-mark as handled */
                    listSignatureTypes.add(strAlreadyHandled);
                    return;
                }
                /** problem referenced to arguments */
                if (strSignature.startsWith("#A")) {
                    listSignatureTypes.add(strAlreadyHandled);
                    holder.registerProblem(objTargetExpression, strProblemResolvingParameterType, ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
                    return;
                }
                /** problem referenced to array item */
                if (strSignature.startsWith("#E")) {
                    listSignatureTypes.add(strAlreadyHandled);
                    holder.registerProblem(objTargetExpression, strProblemResolvingArrayItemType, ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
                    return;
                }
                /** lookup core types and classes */
                if (strSignature.startsWith("#C")) {
                    String strTypeExtracted = strSignature.replace("#C", "");
                    listSignatureTypes.add(strTypeExtracted);
                    return;
                }
                /** problem referenced to calls resolving */
                if (strSignature.contains("?")) {
                    listSignatureTypes.add(strAlreadyHandled);
                    holder.registerProblem(objTargetExpression, strProblemResolvingClassSlotType, ProblemHighlightType.ERROR);
                    return;
                }


                /** === more complex analysis - classes are here === */
                /** lookup for property or method */
                final boolean isProperty = strSignature.startsWith("#P");
                final boolean isMethod = strSignature.startsWith("#M");
                if (isProperty || isMethod) {
                    /** check signature structure */
                    String[] arrParts = strSignature.split("#C");
                    if (arrParts.length != 2) {
                        /** lookup failed - eg not recognizable behind reflection */
                        listSignatureTypes.add(strAlreadyHandled);
                        holder.registerProblem(objTargetExpression, strProblemResolvingClassSlot, ProblemHighlightType.ERROR);
                        return;
                    }
                    arrParts = arrParts[1].split("\\.");
                    if (arrParts.length != 2) {
                        /** TODO: resolve chain type, un-mark as handled */
                        listSignatureTypes.add(strAlreadyHandled);
                        return;
                    }

                    String strClassName = arrParts[0];
                    String strSlotName  = arrParts[1];

                    String strAllTypes = "";
                    Collection<PhpClass> objClasses = PhpIndex.getInstance(holder.getProject()).getClassesByName(strClassName);
                    /** resolve the slot in known classes */
                    if (objClasses.size() > 0) {
                        for (PhpClass objClass : objClasses) {
                            if (isMethod) {
                                for (Method objMethod : objClass.getMethods()) {
                                    if (objMethod.getName().equals(strSlotName)) {
                                        strAllTypes += "|" + objMethod.getType().toString();
                                    }
                                }
                            }

                            if (isProperty) {
                                for (Field objField : objClass.getFields()) {
                                    if (objField.getName().equals(strSlotName)) {
                                        strAllTypes += "|" + objField.getType().toString();
                                    }
                                }
                            }
                        }
                    } else {
                        listSignatureTypes.add("\\class-not-resolved");
                    }

                    this.lookupType(strAllTypes, objTargetExpression, listSignatureTypes);
                    return;
                }

                /** TODO: remove error logging */
                holder.registerProblem(objTargetExpression, "not handled: " + strSignature, ProblemHighlightType.ERROR);
            }
        };
    }
}
