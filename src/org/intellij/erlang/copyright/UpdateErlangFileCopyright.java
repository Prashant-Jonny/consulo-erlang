/*
 * Copyright 2012-2013 Sergey Ignatov
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

package org.intellij.erlang.copyright;

import org.intellij.erlang.psi.ErlangFile;
import org.jetbrains.annotations.NotNull;
import org.mustbe.consulo.copyright.config.CopyrightFileConfig;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.maddyhome.idea.copyright.CopyrightProfile;
import com.maddyhome.idea.copyright.psi.UpdatePsiFileCopyright;

public class UpdateErlangFileCopyright extends UpdatePsiFileCopyright<CopyrightFileConfig>
{

	public UpdateErlangFileCopyright(@NotNull PsiFile psiFile, @NotNull CopyrightProfile copyrightProfile)
	{
		super(psiFile, copyrightProfile);
	}

	@Override
	protected boolean accept()
	{
		return getFile() instanceof ErlangFile;
	}

	@Override
	protected void scanFile()
	{
		PsiElement first = getFile().getFirstChild();
		PsiElement last = first;
		PsiElement next = first;
		while(next != null)
		{
			if(next instanceof PsiComment || next instanceof PsiWhiteSpace)
			{
				next = getNextSibling(next);
			}
			else
			{
				break;
			}
			last = next;
		}

		if(first != null)
		{
			checkComments(first, last, true);
		}
		else
		{
			checkComments(null, null, true);
		}
	}
}