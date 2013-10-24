/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.jetbrains.python.codeInsight.liveTemplates;

import com.intellij.codeInsight.template.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.PyFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author yole
 */
public class PyFunctionNameMacro extends Macro {
  @Override
  public String getName() {
    return "pyFunctionName";
  }

  @Override
  public String getPresentableName() {
    return "pyFunctionName()";
  }

  @Nullable
  @Override
  public Result calculateResult(@NotNull Expression[] params, ExpressionContext context) {
    PsiElement place = context.getPsiElementAtStartOffset();
    PyFunction pyFunction = PsiTreeUtil.getParentOfType(place, PyFunction.class);
    if (pyFunction == null) {
      return null;
    }
    String name = pyFunction.getName();
    return name == null ? null : new TextResult(name);
  }
}
