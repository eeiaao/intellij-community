// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.ui;

import com.intellij.codeInsight.TailType;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.codeInsight.lookup.TailTypeDecorator;
import com.intellij.execution.JavaRunConfigurationBase;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.vmOptions.*;
import com.intellij.ide.actions.EditCustomVmOptionsAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.ex.PathUtilEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VmOptionsCompletionContributor extends CompletionContributor implements DumbAware {
  private static final Pattern OPTION_SEPARATOR = Pattern.compile("\\s+");
  private static final Pattern OPTION_MATCHER = Pattern.compile("^-XX:[+\\-]?(\\w+)(=.+)?$");

  @Override
  public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    Document document = parameters.getEditor().getDocument();
    String jrePath = getJrePathForVmOptionsDocument(document);
    if (jrePath == null) return;
    CompletableFuture<JdkOptionsData> jdk = VMOptionsService.getInstance().getOrComputeOptionsForJdk(jrePath);
    JdkOptionsData data = ProgressIndicatorUtils.awaitWithCheckCanceled(jdk);
    if (data == null) return;
    int offset = parameters.getOffset();
    String currentText = document.getText();
    while (offset > 0 && Character.isAlphabetic(currentText.charAt(offset - 1))) {
      offset--;
    }
    if (addXxCompletion(result, data, offset, currentText) || addSimpleOptions(result, data, offset, currentText)) {
      result.stopHere();
    }
  }

  private static boolean addSimpleOptions(@NotNull CompletionResultSet result,
                                          @NotNull JdkOptionsData data,
                                          int offset,
                                          @NotNull String text) {
    String[] prefixes = {"--", "-", ""};
    for (String prefix : prefixes) {
      if (hasOptionPrefix(text, offset, prefix)) {
        addDashOptions(result, data, prefix);
        return true;
      }
    }
    return false;
  }

  private static void addDashOptions(@NotNull CompletionResultSet result, @NotNull JdkOptionsData data, String prefix) {
    String[] options = {"ea", "enableassertions", "da", "disableassertions", "esa", "dsa", "agentpath:", "agentlib:",
      "javaagent:", "XX:", "D"};
    Stream<VMOption> stream = Stream.concat(
      data.getOptions().stream().filter(option -> option.getVariant() != VMOptionVariant.XX),
      Stream.of(options).map(opt -> new VMOption(opt, null, null, VMOptionKind.Product, null, VMOptionVariant.DASH)));
    stream.forEach(option -> {
      String fullLookup = option.getVariant().prefix() + option.getOptionName();
      if (!fullLookup.startsWith(prefix)) return;
      String lookup = fullLookup.substring(prefix.length());
      result.addElement(LookupElementBuilder.create(option, lookup).withPresentableText(fullLookup));
    });
  }

  private static boolean addXxCompletion(@NotNull CompletionResultSet result,
                                         @NotNull JdkOptionsData data,
                                         int offset,
                                         @NotNull String text) {
    boolean booleanStart = false;
    if (offset > 0 && (text.charAt(offset - 1) == '+' || text.charAt(offset - 1) == '-')) {
      offset--;
      booleanStart = true;
    }
    if (!hasOptionPrefix(text, offset, "-XX:")) return false;
    List<LookupElement> elements = new ArrayList<>();
    Set<String> existingOptions = OPTION_SEPARATOR.splitAsStream(text).map(OPTION_MATCHER::matcher)
      .filter(Matcher::matches).map(matcher -> matcher.group(1)).collect(Collectors.toSet());
    for (VMOption option : data.getOptions()) {
      if (option.getVariant() != VMOptionVariant.XX) continue;
      if (existingOptions.contains(option.getOptionName())) continue;
      String type = option.getType();
      LookupElementBuilder e = null;
      TailType tailType = null;
      Icon icon = option.getKind().icon();
      if ("bool".equals(type)) {
        String lookupString = (booleanStart ? "" : Boolean.parseBoolean(option.getDefaultValue()) ? "-" : "+") + option.getOptionName();
        tailType = TailType.SPACE;
        e = LookupElementBuilder.create(option, lookupString);
      }
      else if (!booleanStart) {
        String tailText = " = " + option.getDefaultValue();
        tailType = TailType.EQUALS;
        e = LookupElementBuilder.create(option, option.getOptionName()).withTailText(tailText, true);
      }
      if (e != null) {
        LookupElement element = TailTypeDecorator.withTail(e.withTypeText(type).withIcon(icon), tailType);
        InsertHandler<LookupElement> handler = getInsertHandler(option.getKind());
        if (handler != null) {
          element = LookupElementDecorator.withDelegateInsertHandler(element, handler);
        }
        elements.add(element);
      }
    }
    result = result.withPrefixMatcher(new CamelHumpMatcher(result.getPrefixMatcher().getPrefix(), false));
    result.addAllElements(elements);
    return true;
  }

  private static InsertHandler<LookupElement> getInsertHandler(VMOptionKind kind) {
    String unlockOption = kind.unlockOption();
    return unlockOption == null ? null : (context, item) -> unlock(context, unlockOption);
  }

  private static void unlock(InsertionContext context, String option) {
    Document document = context.getDocument();
    if (document.getCharsSequence().toString().contains(option)) return;
    document.insertString(0, option + (context.getEditor().isOneLineMode() ? " " : "\n"));
  }

  private static boolean hasOptionPrefix(@NotNull CharSequence sequence, int offset, @NotNull String xxPrefix) {
    return offset >= xxPrefix.length() && sequence.subSequence(offset - xxPrefix.length(), offset).toString().equals(xxPrefix) &&
           (offset == xxPrefix.length() || Character.isWhitespace(sequence.charAt(offset - xxPrefix.length() - 1)));
  }

  @Nullable
  private static String getJrePathForVmOptionsDocument(Document document) {
    String path = document.getUserData(EditCustomVmOptionsAction.Companion.getVmPathKey());
    if (path != null) {
      return path;
    }
    JavaRunConfigurationBase settings = document.getUserData(VmOptionsEditor.SETTINGS_KEY);
    if (settings == null) return null;
    return getJrePath(settings);
  }

  @Nullable
  private static String getJrePath(JavaRunConfigurationBase settings) {
    String jrePath = null;
    Sdk sdk;
    if (settings.isAlternativeJrePathEnabled()) {
      jrePath = settings.getAlternativeJrePath();
      sdk = jrePath == null ? null : ProjectJdkTable.getInstance().findJdk(jrePath);
    }
    else {
      Module module = settings.getConfigurationModule().getModule();
      if (module != null) {
        sdk = JavaParameters.getJdkToRunModule(module, false);
      }
      else {
        sdk = PathUtilEx.getAnyJdk(settings.getProject());
      }
    }
    if (sdk != null) {
      jrePath = sdk.getHomePath();
    }
    return jrePath;
  }
}
