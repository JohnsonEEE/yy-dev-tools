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

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.application.ReadActionProcessor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.search.CustomPropertyScopeProvider;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchRequestCollector;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

/**
 * @author yi.yi
 * @date 2021.11.22
 */
public class GWTMethodUsagesSearcher extends QueryExecutorBase <PsiReference, MethodReferencesSearch.SearchParameters> {
    @Override
    public void processQuery(@NotNull MethodReferencesSearch.SearchParameters p, @NotNull Processor<? super PsiReference> consumer) {
        PsiMethod method = p.getMethod();
        boolean[] isConstructor = new boolean[1];
        PsiManager[] psiManager = new PsiManager[1];
        String[] methodName = new String[1];
        boolean[] isValueAnnotation = new boolean[1];
        boolean[] needStrictSignatureSearch = new boolean[1];
        boolean strictSignatureSearch = p.isStrictSignatureSearch();

        PsiClass aClass = DumbService.getInstance(p.getProject()).runReadActionInSmartMode(() -> {
            PsiClass aClass1 = method.getContainingClass();
            if (aClass1 == null) return null;
            isConstructor[0] = method.isConstructor();
            psiManager[0] = aClass1.getManager();
            methodName[0] = method.getName();
            isValueAnnotation[0] = PsiUtil.isAnnotationMethod(method) &&
                    PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME.equals(methodName[0]) &&
                    method.getParameterList().isEmpty();
            needStrictSignatureSearch[0] = strictSignatureSearch && (aClass1 instanceof PsiAnonymousClass
                    || aClass1.hasModifierProperty(PsiModifier.FINAL)
                    || method.hasModifierProperty(PsiModifier.STATIC)
                    || method.hasModifierProperty(PsiModifier.FINAL)
                    || method.hasModifierProperty(PsiModifier.PRIVATE));
            return aClass1;
        });
        if (aClass == null) return;

        SearchRequestCollector collector = p.getOptimizer();

        SearchScope searchScope = DumbService.getInstance(p.getProject()).runReadActionInSmartMode(p::getEffectiveSearchScope);
        if (searchScope == GlobalSearchScope.EMPTY_SCOPE) {
            return;
        }

        if (isConstructor[0]) {
            new GWTConstructorReferencesSearchHelper (psiManager[0]).
                    processConstructorReferences(consumer, method, aClass, searchScope, p.getProject(), false, strictSignatureSearch, collector);
        }

        if (isValueAnnotation[0]) {
            Processor <PsiReference> refProcessor = createImplicitDefaultAnnotationMethodConsumer(consumer);
            ReferencesSearch.search(aClass, searchScope).forEach(refProcessor);
        }

        if (needStrictSignatureSearch[0]) {
            ReferencesSearch.searchOptimized(method, searchScope, false, collector, consumer);
            return;
        }

        if (StringUtil.isEmpty(methodName[0])) {
            return;
        }

        DumbService.getInstance(p.getProject()).runReadActionInSmartMode(()-> {
            PsiMethod[] methods = strictSignatureSearch ? new PsiMethod[]{method} : aClass.findMethodsByName(methodName[0], false);

            PsiSearchHelper psiSearchHelper = PsiSearchHelper.getInstance(p.getProject());
            short searchContext = UsageSearchContext.IN_CODE | UsageSearchContext.IN_COMMENTS | UsageSearchContext.IN_FOREIGN_LANGUAGES;
            for (PsiMethod m : methods) {
                SearchScope methodUseScope = psiSearchHelper.getUseScope(m);
                collector.searchWord(methodName[0], searchScope.intersectWith(methodUseScope), searchContext, true, m,
                        getTextOccurrenceProcessor(new PsiMethod[] {m}, aClass, strictSignatureSearch));
            }

            SearchScope accessScope = psiSearchHelper.getUseScope(methods[0]);
            for (int i = 1; i < methods.length; i++) {
                PsiMethod method1 = methods[i];
                accessScope = accessScope.union(psiSearchHelper.getUseScope(method1));
            }
            SearchScope restrictedByAccessScope = searchScope.intersectWith(accessScope);
            addPropertyAccessUsages(method, restrictedByAccessScope, collector);
            return null;
        });
    }

    @NotNull
    protected GWTMethodTextOccurrenceProcessor getTextOccurrenceProcessor(PsiMethod @NotNull [] methods, @NotNull PsiClass aClass, boolean strictSignatureSearch) {
        return new GWTMethodTextOccurrenceProcessor(aClass, strictSignatureSearch, methods);
    }

    @NotNull
    private ReadActionProcessor <PsiReference> createImplicitDefaultAnnotationMethodConsumer(@NotNull Processor<? super PsiReference> consumer) {
        return new ReadActionProcessor<>() {
            @Override
            public boolean processInReadAction(final PsiReference reference) {
                if (reference instanceof PsiJavaCodeReferenceElement) {
                    PsiJavaCodeReferenceElement javaReference = (PsiJavaCodeReferenceElement)reference;
                    if (javaReference.getParent() instanceof PsiAnnotation) {
                        PsiNameValuePair[] members = ((PsiAnnotation)javaReference.getParent()).getParameterList().getAttributes();
                        if (members.length == 1 && members[0].getNameIdentifier() == null) {
                            PsiReference t = members[0].getReference();
                            if (t != null && !consumer.process(t)) return false;
                        }
                    }
                }
                return true;
            }
        };
    }

    private void addPropertyAccessUsages(@NotNull PsiMethod method, @NotNull SearchScope scope, @NotNull SearchRequestCollector collector) {
        final String propertyName = PropertyUtilBase.getPropertyName(method);
        if (StringUtil.isNotEmpty(propertyName)) {
            SearchScope additional = GlobalSearchScope.EMPTY_SCOPE;
            for (CustomPropertyScopeProvider provider : CustomPropertyScopeProvider.EP_NAME.getExtensionList()) {
                additional = additional.union(provider.getScope(method.getProject()));
            }

            SearchScope propScope = scope.intersectWith(method.getUseScope()).intersectWith(additional);
            collector.searchWord(propertyName, propScope, UsageSearchContext.IN_FOREIGN_LANGUAGES, true, method);
        }
    }
}
