package me.serce.solidity.ide.hints

import com.intellij.codeInsight.TargetElementUtil
import me.serce.solidity.utils.SolTestBase
import org.intellij.lang.annotations.Language


class SolDocumentationProviderTest : SolTestBase() {
  fun testDocumentation() = checkByText("""
          contract A {
          
              function main() {
                  withDoc/*caret*/();
              }
              /**
                my docs
              */
              function withDoc(int myInti, bool aBool) returns (uint) {
                 return 1;
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc(<b style='color:rgb(0,0,128)'>int</b> myInti, <b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>bool</b></b> aBool)  <b style='color:rgb(0,0,128)'>returns</b> (<b style='color:rgb(0,0,128)'>uint</b>)</pre></div><div class='content'><p>my docs</div>""")

  fun testStateVariableUsedInFunction() = checkByText("""
          contract A {
              /// @notice the owner
              address owner;
              function main() {
                  own/*caret*/er;
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>address</b></b> owner</pre></div><div class='content'><p>the owner</div>""")

  fun testLocalVariableUsedInFunction() = checkByText("""
          contract A {
              function main() {
                  uint x = 1;
                  x/*caret*/;
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>uint</b> x</pre></div>""")

  fun testDocsForBuiltinFunction() = checkByText("""
            contract A {
                function main() {
                    abi.encode/*caret*/();
                }
            }
        """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> encode(...)  <b style='color:rgb(0,0,128)'>returns</b> (<b style='color:rgb(0,0,128)'>bytes</b> <b style='color:rgb(0,0,128)'>memory</b>)</pre></div><div class='content'><p>ABI-encodes the given arguments</div>""")

  fun testTripleSlashDocs() = checkByText("""
          contract A {
          
              function main() {
                  withDoc/*caret*/();
              }
              /// @notice my docs
              function withDoc(int myInti, bool aBool) returns (uint) {
                 return 1;
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc(<b style='color:rgb(0,0,128)'>int</b> myInti, <b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>bool</b></b> aBool)  <b style='color:rgb(0,0,128)'>returns</b> (<b style='color:rgb(0,0,128)'>uint</b>)</pre></div><div class='content'><p>my docs</div>""")

  fun testTagsWithoutSpaceAfterSlashes() = checkByText("""
          contract A {
              function main() {
                  withDoc/*caret*/();
              }
              ///@notice hi
              ///@dev there
              function withDoc() { }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc()  </pre></div><div class='content'><p>hi<p>there</div>""")

  fun testTagsRightAfterBlockCommentStars() = checkByText("""
          contract A {
              function main() {
                  withDoc/*caret*/();
              }
              /**
               *@notice hi
               *@dev there
               */
              function withDoc() { }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc()  </pre></div><div class='content'><p>hi<p>there</div>""")

  fun testTagsAtLineStartInBlockComment() = checkByText("""
          contract A {
              function main() {
                  withDoc/*caret*/();
              }
              /**
              @notice hi
              @dev there
              */
              function withDoc() { }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc()  </pre></div><div class='content'><p>hi<p>there</div>""")

  fun testTabBeforeTag() = checkByText("""
          contract A {
              function main() {
                  withDoc/*caret*/();
              }
              ///	@notice hi
              function withDoc() { }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc()  </pre></div><div class='content'><p>hi</div>""")

  fun testNatSpecSections() = checkByText("""
          contract A {
              function main() {
                  transferFrom/*caret*/(address(0), 1);
              }
              /// @title Token
              /// @author Alice
              /// @notice Moves a token.
              /// @dev Throws unless `msg.sender` owns it,
              ///  or is approved.
              /// @param from The current owner
              /// @param id The token
              /// @return Whether it moved
              /// @custom:security audited
              function transferFrom(address from, uint id) returns (bool) { }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> transferFrom(<b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>address</b></b> from, <b style='color:rgb(0,0,128)'>uint</b> id)  <b style='color:rgb(0,0,128)'>returns</b> (<b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>bool</b></b>)</pre></div><div class='content'><p><b>Token</b><p>Moves a token.<p>Throws unless <code>msg.sender</code> owns it,${"\n"}or is approved.</div>""" +
    """<table class='sections'><tr><td valign='top' class='section'><p>Params:</td><td valign='top'><code>from</code> &ndash; The current owner<br/><code>id</code> &ndash; The token</td>""" +
    """<tr><td valign='top' class='section'><p>Returns:</td><td valign='top'>Whether it moved</td>""" +
    """<tr><td valign='top' class='section'><p>Author:</td><td valign='top'>Alice</td>""" +
    """<tr><td valign='top' class='section'><p>security:</td><td valign='top'>audited</td></table>""")

  fun testInheritDocFillsOnlyMissingTags() = checkByText("""
          interface I {
              /// @notice Base notice
              /// @param from Base from
              /// @param id Base id
              function transferFrom(address from, uint id) external returns (bool);
          }
          contract A is I {
              /// @inheritdoc I
              /// @param id Own id
              function transferFrom(address from, uint id) external override returns (bool) { }
              function main() {
                  this.transferFrom/*caret*/(address(0), 1);
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> transferFrom(<b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>address</b></b> from, <b style='color:rgb(0,0,128)'>uint</b> id) <b style='color:rgb(0,0,128)'>external</b> <b style='color:rgb(0,0,128)'>override</b>  <b style='color:rgb(0,0,128)'>returns</b> (<b style='color:rgb(0,0,128)'><b style='color:rgb(0,0,128)'>bool</b></b>)</pre></div>""" +
    """<div class='content'><p>Base notice</div>""" +
    """<table class='sections'><tr><td valign='top' class='section'><p>Params:</td><td valign='top'><code>id</code> &ndash; Own id<br/><code>from</code> &ndash; Base from</td></table>""")

  fun testInheritedDocs() = checkByText("""
          contract Base {
              /// @notice base docs
              function withDoc(int a) { }
          }
          contract Child is Base {
              /// @inheritdoc
              function withDoc(int a) { }
              function main() {
                  withDoc/*caret*/(1);
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>function</b> withDoc(<b style='color:rgb(0,0,128)'>int</b> a)  </pre></div><div class='content'><p>base docs</div>""")

  fun testParameterDocs() = checkByText("""
          contract A {
              /// @param myInt parameter docs
              function withDoc(uint myInt/*caret*/) { }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>uint</b> myInt</pre></div><div class='content'>parameter docs</div>""")

  fun testErrorDefinitionDocs() = checkByText("""
          contract A {
              error NotEnoughBalance(uint requested, uint available);
              function main() {
                  revert NotEnoughBalance/*caret*/(1, 2);
              }
          }
      """, """<div class='definition'><pre><b style='color:rgb(0,0,128)'>error</b> NotEnoughBalance (uint requested, uint available)</pre></div>""")

  fun testYulBuiltinDocs() = checkYulBuiltin("tst/*caret*/ore(0, 1)", "tstore",
    "<div class='definition'><pre><b style='color:rgb(0,0,128)'>tstore</b>(p, v)</pre></div>" +
      "<div class='content'>transientStorage[p] := v</div>" +
      "<table class='sections'><tr><td valign='top' class='section'><p>EVM version:</td><td valign='top'>since Cancun</td></table>")

  fun testYulBuiltinKeywordDocs() = checkYulBuiltin("pop(addr/*caret*/ess())", "address",
    "<div class='definition'><pre><b style='color:rgb(0,0,128)'>address</b>() -&gt; v</pre></div>" +
      "<div class='content'>address of the current contract / execution context</div>" +
      "<table class='sections'><tr><td valign='top' class='section'><p>EVM version:</td><td valign='top'>all</td></table>")

  fun testDeprecatedYulBuiltinDocs() = checkYulBuiltin("pop(diffi/*caret*/culty())", "difficulty",
    "<div class='definition'><pre><b style='color:rgb(0,0,128)'>difficulty</b>() -&gt; v</pre></div>" +
      "<div class='content'>difficulty of the current block, replaced by prevrandao since Paris</div>" +
      "<table class='sections'><tr><td valign='top' class='section'><p>EVM version:</td><td valign='top'>all, removed in Paris</td>" +
      "<tr><td valign='top' class='section'><p>Deprecated:</td><td valign='top'>yes</td></table>")

  fun testUserYulFunctionHasNoBuiltinDocs() {
    myFixture.configureByText("main.sol", """
      contract A {
          function f() public {
              assembly {
                  function g() {}
                  <caret>g()
              }
          }
      }
    """.trimIndent())
    val context = myFixture.file.findElementAt(myFixture.caretOffset)
    assertNull(SolDocumentationProvider().getCustomDocumentationElement(myFixture.editor, myFixture.file, context, myFixture.caretOffset))
  }

  private fun checkYulBuiltin(statement: String, name: String, expectedDoc: String) {
    myFixture.configureByText("main.sol", """
      contract A {
          function f() public {
              assembly {
                  $statement
              }
          }
      }
    """.trimIndent().replace("/*caret*/", "<caret>"))
    val provider = SolDocumentationProvider()
    val context = myFixture.file.findElementAt(myFixture.caretOffset)
    val element = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, context, myFixture.caretOffset)
    assertNotNull(element)
    assertEquals(name, element!!.firstChild.text)
    assertSameLines(expectedDoc, provider.generateDoc(element, context)!!)
  }

  private fun checkByText(@Language("Solidity") code: String, @Language("HTML") expectedDoc: String) {
    myFixture.configureByText("main.sol", code.replace("/*caret*/", "<caret>"))
    // like the platform: the target is the resolved declaration, the original element is the token at the caret
    val element = myFixture.elementAtCaret
    val offset = TargetElementUtil.adjustOffset(myFixture.file, myFixture.editor.document, myFixture.caretOffset)
    val originalElement = myFixture.file.findElementAt(offset)
    val documentationProvider = SolDocumentationProvider()
    val generateDoc = documentationProvider.generateDoc(element, originalElement)
    assertNotNull(generateDoc)
    assertSameLines(expectedDoc, generateDoc!!)
  }

}
