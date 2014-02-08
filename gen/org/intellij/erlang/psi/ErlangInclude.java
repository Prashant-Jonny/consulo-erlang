// This is a generated file. Not intended for manual editing.
package org.intellij.erlang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.StubBasedPsiElement;
import org.intellij.erlang.stubs.ErlangIncludeStub;

public interface ErlangInclude extends ErlangCompositeElement, StubBasedPsiElement<ErlangIncludeStub> {

  @Nullable
  ErlangIncludeString getIncludeString();

  @Nullable
  ErlangIncludeString getIncludeStringSafe();

}
