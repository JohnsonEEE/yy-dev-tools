/*
 *
 *
 * Copyright ( c ) 2021 TH Supcom Corporation. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of TH Supcom
 * Corporation ("Confidential Information").  You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with TH Supcom Corporation or a TH Supcom
 * authorized reseller (the "License Agreement"). TH Supcom may make changes to the
 * Confidential Information from time to time. Such Confidential Information may
 * contain errors.
 *
 * EXCEPT AS EXPLICITLY SET FORTH IN THE LICENSE AGREEMENT, TH Supcom DISCLAIMS ALL
 * WARRANTIES, COVENANTS, REPRESENTATIONS, INDEMNITIES, AND GUARANTEES WITH
 * RESPECT TO SOFTWARE AND DOCUMENTATION, WHETHER EXPRESS OR IMPLIED, WRITTEN OR
 * ORAL, STATUTORY OR OTHERWISE INCLUDING, WITHOUT LIMITATION, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, TITLE, NON-INFRINGEMENT AND FITNESS FOR A
 * PARTICULAR PURPOSE. TH Supcom DOES NOT WARRANT THAT END USER'S USE OF THE
 * SOFTWARE WILL BE UNINTERRUPTED, ERROR FREE OR SECURE.
 *
 * TH Supcom SHALL NOT BE LIABLE TO END USER, OR ANY OTHER PERSON, CORPORATION OR
 * ENTITY FOR INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY OR CONSEQUENTIAL
 * DAMAGES, OR DAMAGES FOR LOSS OF PROFITS, REVENUE, DATA OR USE, WHETHER IN AN
 * ACTION IN CONTRACT, TORT OR OTHERWISE, EVEN IF TH Supcom HAS BEEN ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES. TH Supcom' TOTAL LIABILITY TO END USER SHALL NOT
 * EXCEED THE AMOUNTS PAID FOR THE TH Supcom SOFTWARE BY END USER DURING THE PRIOR
 * TWELVE (12) MONTHS FROM THE DATE IN WHICH THE CLAIM AROSE.  BECAUSE SOME
 * STATES OR JURISDICTIONS DO NOT ALLOW LIMITATION OR EXCLUSION OF CONSEQUENTIAL
 * OR INCIDENTAL DAMAGES, THE ABOVE LIMITATION MAY NOT APPLY TO END USER.
 *
 * Copyright version 2.0
 */
package org.yiyi;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceService;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.ReferenceRange;
import com.intellij.psi.ResolvingHint;
import com.intellij.psi.search.RequestResultProcessor;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.Processor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * @author yi.yi
 * @date 2021.11.22
 */
public class GWTMethodTextOccurrenceProcessor extends RequestResultProcessor {
    private static final PsiReferenceService ourReferenceService = PsiReferenceService.getService();
    private final PsiMethod[] myMethods;
    protected final PsiClass myContainingClass;
    private final boolean myStrictSignatureSearch;

    public GWTMethodTextOccurrenceProcessor(@NotNull final PsiClass aClass, final boolean strictSignatureSearch, PsiMethod @NotNull ... methods) {
        super(strictSignatureSearch, Arrays.asList(methods));
        myMethods = methods;
        myContainingClass = aClass;
        myStrictSignatureSearch = strictSignatureSearch;
    }

    @Override
    public final boolean processTextOccurrence(@NotNull PsiElement element, int offsetInElement, @NotNull final Processor<? super PsiReference> consumer) {
        for (PsiReference ref : ourReferenceService.getReferences(element, new PsiReferenceService.Hints(myMethods[0], offsetInElement))) {
            if (ReferenceRange.containsOffsetInElement(ref, offsetInElement) && !processReference(consumer, ref)) {
                return false;
            }
        }
        return true;
    }

    private boolean processReference(Processor<? super PsiReference> consumer, PsiReference ref) {
        for (PsiMethod method : myMethods) {
            if (!method.isValid()) {
                continue;
            }

            if (ref instanceof ResolvingHint && !((ResolvingHint)ref).canResolveTo(PsiMethod.class)) {
                return true;
            }

            PsiElement rpcElement = method.getParent ();
            if (rpcElement instanceof PsiClass && !StringUtils.endsWithIgnoreCase (((PsiClass) rpcElement).getQualifiedName (), "Async")) {
                LinkedHashSet <PsiClass> superClasses = InheritanceUtil.getSuperClasses ((PsiClass) rpcElement);
                PsiClass refPsiClass = PsiTreeUtil.getParentOfType (ref.resolve (), PsiClass.class);
                for (PsiClass superClass : superClasses) {
                    if (StringUtils.equals (superClass.getQualifiedName (), "com.google.gwt.user.client.rpc.RemoteService")
                            && refPsiClass != null
                            && StringUtils.equals (refPsiClass.getQualifiedName (), ((PsiClass) rpcElement).getQualifiedName () + "Async")) {
                        return consumer.process(ref);
                    }
                }
            }

            if (ref.isReferenceTo(method)) {
                return consumer.process(ref);
            }

            if (!processInexactReference(ref, ref.resolve(), method, consumer)) {
                return false;
            }
        }

        return true;
    }

    protected boolean processInexactReference(PsiReference ref, PsiElement refElement, PsiMethod method, Processor<? super PsiReference> consumer) {
        if (refElement instanceof PsiMethod) {
            PsiMethod refMethod = (PsiMethod)refElement;
            PsiClass refMethodClass = refMethod.getContainingClass();
            if (refMethodClass == null) return true;

            if (!refMethod.hasModifierProperty(PsiModifier.STATIC)) {
                PsiSubstitutor substitutor = TypeConversionUtil.getClassSubstitutor(myContainingClass, refMethodClass, PsiSubstitutor.EMPTY);
                if (substitutor != null) {
                    MethodSignature superSignature = method.getSignature(substitutor);
                    MethodSignature refSignature = refMethod.getSignature(PsiSubstitutor.EMPTY);

                    if (MethodSignatureUtil.isSubsignature(superSignature, refSignature)) {
                        if (!consumer.process(ref)) return false;
                    }
                }
            }

            if (!myStrictSignatureSearch) {
                PsiManager manager = method.getManager();
                if (manager.areElementsEquivalent(refMethodClass, myContainingClass)) {
                    return consumer.process(ref);
                }
            }
        }

        return true;
    }
}
