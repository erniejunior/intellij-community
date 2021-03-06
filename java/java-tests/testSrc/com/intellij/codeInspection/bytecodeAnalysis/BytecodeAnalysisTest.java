/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.codeInspection.bytecodeAnalysis;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.InferredAnnotationsManager;
import com.intellij.codeInspection.bytecodeAnalysis.data.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.analysis.AnalyzerException;
import org.junit.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.HashMap;

/**
 * @author lambdamix
 */
public class BytecodeAnalysisTest extends JavaCodeInsightFixtureTestCase {
  public static final String ORG_JETBRAINS_ANNOTATIONS_CONTRACT = Contract.class.getName();
  private final String myClassesProjectRelativePath = "/classes/" + Test01.class.getPackage().getName().replace('.', '/');
  private JavaPsiFacade myJavaPsiFacade;
  private InferredAnnotationsManager myInferredAnnotationsManager;
  private BytecodeAnalysisConverter myBytecodeAnalysisConverter;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myJavaPsiFacade = JavaPsiFacade.getInstance(myModule.getProject());
    myInferredAnnotationsManager = InferredAnnotationsManager.getInstance(myModule.getProject());
    myBytecodeAnalysisConverter = BytecodeAnalysisConverter.getInstance();

    setUpDataClasses();
  }

  public void testInference() throws IOException {
    checkAnnotations(Test01.class);
    checkAnnotations(Test02.class);
    checkAnnotations(Test03.class);
  }

  public void testConverter() throws IOException {
    checkCompoundIds(Test01.class);
    checkCompoundIds(TestConverterData.class);
    checkCompoundIds(TestConverterData.StaticNestedClass.class);
    checkCompoundIds(TestConverterData.InnerClass.class);
    checkCompoundIds(TestConverterData.GenericStaticNestedClass.class);
    checkCompoundIds(TestAnnotation.class);
  }

  public void testLeakingParametersAnalysis() throws IOException {
    checkLeakingParameters(LeakingParametersData.class);
  }

  private static void checkLeakingParameters(Class<?> jClass) throws IOException {
    final HashMap<Method, boolean[]> map = new HashMap<Method, boolean[]>();

    // collecting leakedParameters
    final ClassReader classReader = new ClassReader(new FileInputStream(jClass.getResource("/" + jClass.getName().replace('.', '/') + ".class").getFile()));
    classReader.accept(new ClassVisitor(Opcodes.ASM5) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        final MethodNode node = new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions);
        final Method method = new Method(classReader.getClassName(), name, desc);
        return new MethodVisitor(Opcodes.ASM5, node) {
          @Override
          public void visitEnd() {
            super.visitEnd();
            try {
              map.put(method, cfg.leakingParameters(classReader.getClassName(), node));
            }
            catch (AnalyzerException ignore) {}
          }
        };
      }
    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    for (java.lang.reflect.Method jMethod : jClass.getDeclaredMethods()) {
      Method method = new Method(Type.getType(jClass).getInternalName(), jMethod.getName(), Type.getMethodDescriptor(jMethod));
      Annotation[][] annotations = jMethod.getParameterAnnotations();
      for (int i = 0; i < annotations.length; i++) {
        boolean isLeaking = false;
        Annotation[] parameterAnnotations = annotations[i];
        for (Annotation parameterAnnotation : parameterAnnotations) {
          if (parameterAnnotation.annotationType() == ExpectLeaking.class) {
            isLeaking = true;
          }
        }
        assertEquals(method.toString() + " #" + i, isLeaking, map.get(method)[i]);
      }
    }
  }

  private void checkAnnotations(Class<?> javaClass) {
    PsiClass psiClass = myJavaPsiFacade.findClass(javaClass.getName(), GlobalSearchScope.moduleWithLibrariesScope(myModule));
    assertNotNull(psiClass);

    for (java.lang.reflect.Method javaMethod : javaClass.getDeclaredMethods()) {
      PsiMethod psiMethod = psiClass.findMethodsByName(javaMethod.getName(), false)[0];
      Annotation[][] annotations = javaMethod.getParameterAnnotations();

      // not-null parameters
      params: for (int i = 0; i < annotations.length; i++) {
        Annotation[] parameterAnnotations = annotations[i];
        PsiParameter psiParameter = psiMethod.getParameterList().getParameters()[i];
        PsiAnnotation inferredAnnotation = myInferredAnnotationsManager.findInferredAnnotation(psiParameter, AnnotationUtil.NOT_NULL);
        for (Annotation parameterAnnotation : parameterAnnotations) {
          if (parameterAnnotation.annotationType() == ExpectNotNull.class) {
            assertNotNull(javaMethod.toString() + " " + i, inferredAnnotation);
            continue params;
          }
        }
        assertNull(javaMethod.toString() + " " + i, inferredAnnotation);
      }

      // not-null result
      ExpectNotNull expectedAnnotation = javaMethod.getAnnotation(ExpectNotNull.class);
      PsiAnnotation actualAnnotation = myInferredAnnotationsManager.findInferredAnnotation(psiMethod, AnnotationUtil.NOT_NULL);
      assertEquals(javaMethod.toString(), expectedAnnotation == null, actualAnnotation == null);


      // contracts
      ExpectContract expectedContract = javaMethod.getAnnotation(ExpectContract.class);
      PsiAnnotation actualContractAnnotation = myInferredAnnotationsManager.findInferredAnnotation(psiMethod, ORG_JETBRAINS_ANNOTATIONS_CONTRACT);

      assertEquals(expectedContract == null, actualContractAnnotation == null);

      if (expectedContract != null) {
        String expectedContractValue = expectedContract.value();
        String actualContractValue = AnnotationUtil.getStringAttributeValue(actualContractAnnotation, null);
        assertEquals(javaMethod.toString(), expectedContractValue, actualContractValue);
      }

    }
  }

  private void checkCompoundIds(Class<?> javaClass) throws IOException {
    String javaClassName = javaClass.getCanonicalName();
    PsiClass psiClass = myJavaPsiFacade.findClass(javaClassName, GlobalSearchScope.moduleWithLibrariesScope(myModule));
    assertNotNull(psiClass);

    for (java.lang.reflect.Method javaMethod : javaClass.getDeclaredMethods()) {
      Method method = new Method(Type.getType(javaClass).getInternalName(), javaMethod.getName(), Type.getMethodDescriptor(javaMethod));
      boolean noKey = javaMethod.getAnnotation(ExpectNoPsiKey.class) != null;
      PsiMethod psiMethod = psiClass.findMethodsByName(javaMethod.getName(), false)[0];
      checkCompoundId(method, psiMethod, noKey);
    }

    for (Constructor<?> constructor : javaClass.getDeclaredConstructors()) {
      Method method = new Method(Type.getType(javaClass).getInternalName(), "<init>", Type.getConstructorDescriptor(constructor));
      boolean noKey = constructor.getAnnotation(ExpectNoPsiKey.class) != null;
      PsiMethod[] constructors = psiClass.getConstructors();
      PsiMethod psiMethod = constructors[0];
      checkCompoundId(method, psiMethod, noKey);
    }
  }

  private void checkCompoundId(Method method, PsiMethod psiMethod, boolean noKey) throws IOException {
    Direction direction = new Out();
    int psiKey = myBytecodeAnalysisConverter.mkPsiKey(psiMethod, direction);
    if (noKey) {
      assertTrue(-1 == psiKey);
      return;
    }
    else {
      assertFalse(-1 == psiKey);
    }

    int asmKey = myBytecodeAnalysisConverter.mkAsmKey(new Key(method, direction, true));

    Assert.assertEquals(asmKey, psiKey);
  }

  private void setUpDataClasses() throws IOException {
    File classesDir = new File(Test01.class.getResource("/" + Test01.class.getPackage().getName().replace('.', '/')).getFile());
    File destDir = new File(myModule.getProject().getBaseDir().getPath() + myClassesProjectRelativePath);
    FileUtil.copyDir(classesDir, destDir);
    VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(destDir);
    assertNotNull(vFile);
    PsiTestUtil.addLibrary(myModule, "dataClasses", vFile.getPath(), new String[]{""}, ArrayUtil.EMPTY_STRING_ARRAY);
  }

}
