package org.mustbe.consulo.erlang.rebar.bundle;

import javax.swing.Icon;

import org.intellij.erlang.ErlangIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.projectRoots.SdkType;

/**
 * @author VISTALL
 * @since 08.02.14
 */
public class RebarBundleType extends SdkType
{
	public RebarBundleType()
	{
		super("REBAR_BUNDLE");
	}

	@Override
	public boolean isValidSdkHome(String s)
	{
		return true;
	}

	@Nullable
	@Override
	public String getVersionString(String s)
	{
		return null;
	}

	@Override
	public String suggestSdkName(String s, String s2)
	{
		return null;
	}

	@NotNull
	@Override
	public String getPresentableName()
	{
		return "Rebar";
	}

	@Nullable
	@Override
	public Icon getIcon()
	{
		return ErlangIcons.REBAR;
	}
}
