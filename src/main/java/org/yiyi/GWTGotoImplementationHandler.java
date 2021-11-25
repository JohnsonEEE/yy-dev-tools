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

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.navigation.BackgroundUpdaterTask;
import com.intellij.codeInsight.navigation.GotoImplementationHandler;
import com.intellij.codeInsight.navigation.ImplementationSearcher;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.psi.ElementDescriptionUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.PsiClassImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.usageView.UsageViewShortNameLocation;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

/**
 * @author yi.yi
 * @date 2021.11.23
 */
public class GWTGotoImplementationHandler extends GotoImplementationHandler {
    @Override
    public @Nullable GotoData getSourceAndTargetElements (@NotNull Editor editor, PsiFile file) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement source = TargetElementUtil.getInstance().findTargetElement(editor, ImplementationSearcher.getFlags(), offset);

        // 这里匹配的是gwt的rpcAsync类
        if (source instanceof PsiMethod) {
            PsiClass parentPsiClass = PsiTreeUtil.getParentOfType (source, PsiClass.class);
            if (parentPsiClass instanceof PsiClassImpl) {
                String asyncQualifiedName = parentPsiClass.getQualifiedName ();
                if (StringUtils.endsWithIgnoreCase (asyncQualifiedName, "Async")) {
                    String rpcQualifiedName = StringUtils.substringBeforeLast (asyncQualifiedName, "Async");
                    Optional <PsiClass> rpcClassOpt = JavaUtils.findClazz (parentPsiClass.getProject (), rpcQualifiedName);
                    if (rpcClassOpt.isPresent ()) {
                        PsiClass rpcPsiClass = rpcClassOpt.get ();
                        PsiMethod[] methods = rpcPsiClass.getMethods ();
                        for (PsiMethod m : methods) {
                            if (StringUtils.equals (m.getName (), ((PsiMethod) source).getName ())) {
                                source = m;
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (source == null) {
            offset = tryGetNavigationSourceOffsetFromGutterIcon(editor, IdeActions.ACTION_GOTO_IMPLEMENTATION);
            if (offset >= 0) {
                source = TargetElementUtil.getInstance().findTargetElement(editor, ImplementationSearcher.getFlags(), offset);
            }
        }
        if (source == null) return null;
        return createDataForSource(editor, offset, source);
    }

    protected @NotNull GotoData createDataForSource(@NotNull Editor editor, int offset, PsiElement source) {
        PsiReference refTemp = TargetElementUtil.findReference(editor, offset);
        if (refTemp != null) {
            PsiElement pe = refTemp.resolve ();
            if (pe != null) {
                PsiElement parent = pe.getParent ();
                if (parent instanceof PsiClassImpl) {
                    String asyncQualifiedName = ((PsiClassImpl) pe.getParent ()).getQualifiedName ();
                    if (StringUtils.endsWithIgnoreCase (asyncQualifiedName, "Async")) {
                        String rpcQualifiedName = StringUtils.substringBeforeLast (asyncQualifiedName, "Async");
                        Optional <PsiClass> rpcClassOpt = JavaUtils.findClazz (pe.getParent ().getProject (), rpcQualifiedName);
                        if (rpcClassOpt.isPresent ()) {
                            refTemp = null;
                        }
                    }
                }
            }
        }
        final PsiReference reference = refTemp;
        final TargetElementUtil instance = TargetElementUtil.getInstance();
        PsiElement[] targets = GWTGotoImplementationHandler.searchImplementations (editor, source, offset,
                new ImplementationSearcher.FirstImplementationsSearcher() {
            @Override
            protected boolean accept(PsiElement element) {
                if (reference != null && !reference.getElement().isValid()) return false;
                return instance.acceptImplementationForReference(reference, element);
            }

            @Override
            protected boolean canShowPopupWithOneItem(PsiElement element) {
                return false;
            }
        });
        if (targets == null) {
            //canceled search
            GotoData data = new GotoData(source, PsiElement.EMPTY_ARRAY, Collections.emptyList());
            return data;
        }
        GotoData gotoData = new GotoData(source, targets, Collections.emptyList());
        gotoData.listUpdaterTask = new ImplementationsUpdaterTask(gotoData, editor, offset, reference) {
            @Override
            public void onSuccess() {
                super.onSuccess();
                PsiElement oneElement = getTheOnlyOneElement();
                if (oneElement != null && navigateToElement(oneElement)) {
                    myPopup.cancel();
                }
            }
        };
        return gotoData;
    }

    static PsiElement @Nullable [] searchImplementations(Editor editor, PsiElement element, int offset, ImplementationSearcher implementationSearcher) {
        TargetElementUtil targetElementUtil = TargetElementUtil.getInstance();
        boolean onRef = ReadAction.compute(() -> targetElementUtil.findTargetElement(editor, ImplementationSearcher.getFlags() & ~(TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED | TargetElementUtil.LOOKUP_ITEM_ACCEPTED), offset) == null);
        return implementationSearcher.searchImplementations(element, editor, onRef && ReadAction.compute(() -> element == null || targetElementUtil.includeSelfInGotoImplementation(element)), onRef);
    }

    private class ImplementationsUpdaterTask extends BackgroundUpdaterTask {
        private final Editor myEditor;
        private final int myOffset;
        private final GotoData myGotoData;
        private final PsiReference myReference;

        ImplementationsUpdaterTask(@NotNull GotoData gotoData, @NotNull Editor editor, int offset, final PsiReference reference) {
            super(
                    gotoData.source.getProject(),
                    ImplementationSearcher.getSearchingForImplementations(),
                    createImplementationComparator(gotoData)
            );
            myEditor = editor;
            myOffset = offset;
            myGotoData = gotoData;
            myReference = reference;
        }

        @Override
        public void run(@NotNull final ProgressIndicator indicator) {
            super.run(indicator);
            for (PsiElement element : myGotoData.targets) {
                if (!updateComponent(element)) {
                    return;
                }
            }
            new ImplementationSearcher.BackgroundableImplementationSearcher() {
                @Override
                protected void processElement(PsiElement element) {
                    indicator.checkCanceled();
                    if (!TargetElementUtil.getInstance().acceptImplementationForReference(myReference, element)) return;
                    if (myGotoData.addTarget(element)) {
                        if (!updateComponent(element)) {
                            indicator.cancel();
                        }
                    }
                }
            }.searchImplementations(myEditor, myGotoData.source, myOffset);
        }

        @Override
        public String getCaption(int size) {
            String name = ElementDescriptionUtil.getElementDescription(myGotoData.source, UsageViewShortNameLocation.INSTANCE);
            return getChooserTitle(myGotoData.source, name, size, isFinished());
        }
    }

    private static @NotNull Comparator<PsiElement> createImplementationComparator(@NotNull GotoData gotoData) {
        Comparator<PsiElement> projectContentComparator = projectElementsFirst(gotoData.source.getProject());
        Comparator<PsiElement> presentationComparator = Comparator.comparing(element -> gotoData.getComparingObject(element));
        Comparator<PsiElement> positionComparator = PsiUtilCore::compareElementsByPosition;
        Comparator<PsiElement> result = projectContentComparator.thenComparing(presentationComparator).thenComparing(positionComparator);
        return wrapIntoReadAction(result);
    }

    public static @NotNull Comparator<PsiElement> projectElementsFirst(@NotNull Project project) {
        FileIndexFacade index = FileIndexFacade.getInstance(project);
        return Comparator.comparing((PsiElement element) -> index.isInContent(element.getContainingFile().getVirtualFile())).reversed();
    }

    private static <T> @NotNull Comparator<T> wrapIntoReadAction(@NotNull Comparator<T> base) {
        return (e1, e2) -> ReadAction.compute(() -> base.compare(e1, e2));
    }
}
