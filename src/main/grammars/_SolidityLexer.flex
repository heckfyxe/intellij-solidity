package me.serce.solidity;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static me.serce.solidity.lang.core.SolidityTokenTypes.*;

%%

%{
  public _SolidityLexer() {
    this((java.io.Reader)null);
  }
%}

%{
  // indicates the start of the comment
  private int zzBlockCommentStarted = -1;

  private static boolean isNatSpecTagStart(char previous) {
    return Character.isWhitespace(previous) || previous == '*' || previous == '/';
  }
%}

%public
%class _SolidityLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

%state PRAGMA_OPEN
%state PRAGMA_REST

EOL=\R
%state IN_BLOCK_COMMENT
%state IN_EOL_COMMENT

WHITE_SPACE=\s+

// see https://docs.soliditylang.org/en/v0.8.7/natspec-format.html for NatSpec tags support
NAT_SPEC_TAG=@custom:[a-z][a-z0-9-]*|@[a-zA-Z_0-9:]*
HEXLITERAL=(hex\"([_0-9a-fA-F]*)\"|hex\'([_0-9a-fA-F]*)\')
// a backslash before a line break continues the string on the next line
STRINGLITERAL=(\"([^\"\r\n\\]|\\(.|\r\n|\r|\n))*\")|(\'([^\'\r\n\\]|\\(.|\r\n|\r|\n))*\')|unicode(\"([^\"\r\n\\]|\\(.|\r\n|\r|\n))*\")|unicode(\'([^\'\r\n\\]|\\(.|\r\n|\r|\n))*\')
// a string still being typed: lexed as a string up to the end of the line, so it stays highlighted and quotes pair
UNTERMINATED_STRINGLITERAL=(\"([^\"\r\n\\]|\\(.|\r\n|\r|\n))*)|(\'([^\'\r\n\\]|\\(.|\r\n|\r|\n))*)
DECIMALNUMBER=[0-9][_0-9]*
FIXEDNUMBER=(([0-9][_0-9]*)+\.[_0-9]*|([0-9][_0-9]*)*\.([0-9][_0-9]*))
SCIENTIFICNUMBER=((([0-9][_0-9]*)+|([0-9][_0-9]*)+\.[_0-9]*|([0-9][_0-9]*|[0-9])*\.([0-9][_0-9]*))[Ee][+-]?[_0-9]+)
HEXNUMBER=(0[xX][_0-9a-fA-F]+)
NUMBERUNIT=(wei|gwei|ether|seconds|minutes|hours|days|weeks|years)
INTNUMTYPE=int(8|16|24|32|40|48|56|64|72|80|88|96|104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?
UINTNUMTYPE=uint(8|16|24|32|40|48|56|64|72|80|88|96|104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?
BYTENUMTYPE=byte(1|2|3|4|5|6|7|8|9|10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29|30|31|32)?
BYTESNUMTYPE=bytes(1|2|3|4|5|6|7|8|9|10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29|30|31|32)?
FIXEDNUMTYPE=fixed([1-9][0-9]*x[0-9]+)?
UFIXEDNUMTYPE=ufixed([1-9][0-9]*x[0-9]+)?
BOOLEANLITERAL=true|false
SPACE=[ \t\n\x0B\f\r]+
IDENTIFIER=[a-zA-Z_$][a-zA-Z_$0-9]*
// Pragma parses anything up to the trailing ';' to be fully forward-compatible.
PRAGMAALL=[^ ][^;]*

%%
<YYINITIAL> {
  {WHITE_SPACE}           { return WHITE_SPACE; }

  "."                     { return DOT; }
  ":"                     { return COLON; }
  ";"                     { return SEMICOLON; }
  ","                     { return COMMA; }
  "+"                     { return PLUS; }
  "-"                     { return MINUS; }
  "*"                     { return MULT; }
  "/"                     { return DIV; }
  "**"                    { return EXPONENT; }
  "!"                     { return NOT; }
  "="                     { return ASSIGN; }
  "=>"                    { return TO; }
  "=="                    { return EQ; }
  "!="                    { return NEQ; }
  "++"                    { return INC; }
  "--"                    { return DEC; }
  "+="                    { return PLUS_ASSIGN; }
  "-="                    { return MINUS_ASSIGN; }
  "*="                    { return MULT_ASSIGN; }
  "/="                    { return DIV_ASSIGN; }
  "|="                    { return OR_ASSIGN; }
  "^="                    { return XOR_ASSIGN; }
  "&="                    { return AND_ASSIGN; }
  "<<="                   { return LSHIFT_ASSIGN; }
  ">>="                   { return RSHIFT_ASSIGN; }
  "%="                    { return PERCENT_ASSIGN; }
  "<"                     { return LESS; }
  "<="                    { return LESSEQ; }
  ">"                     { return MORE; }
  ">="                    { return MOREEQ; }
  "^"                     { return CARET; }
  "&"                     { return AND; }
  "&&"                    { return ANDAND; }
  "|"                     { return OR; }
  "||"                    { return OROR; }
  "["                     { return LBRACKET; }
  "]"                     { return RBRACKET; }
  "{"                     { return LBRACE; }
  "}"                     { return RBRACE; }
  "("                     { return LPAREN; }
  ")"                     { return RPAREN; }
  "?"                     { return QUESTION; }
  "%"                     { return PERCENT; }
  "~"                     { return TILDE; }
  "<<"                    { return LSHIFT; }
  ">>"                    { return RSHIFT; }
  ":="                    { return LEFT_ASSEMBLY; }
  "=:"                    { return RIGHT_ASSEMBLY; }
  "/*"                    {
                            yybegin(IN_BLOCK_COMMENT);
                            yypushback(2);
                            zzBlockCommentStarted = zzCurrentPos;
                          }
  "//"                    {
                            yybegin(IN_EOL_COMMENT);
                            yypushback(2);
                          }
  "pragma"                {
                            yybegin(PRAGMA_OPEN);
                            return PRAGMA;
                          }
  "as"                    { return AS; }
  "import"                { return IMPORT; }
  "abstract"              { return ABSTRACT; }
  "contract"              { return CONTRACT; }
  "library"               { return LIBRARY; }
  "interface"             { return INTERFACE; }
  "is"                    { return IS; }
  "public"                { return PUBLIC; }
  "internal"              { return INTERNAL; }
  "private"               { return PRIVATE; }
  "constant"              { return CONSTANT; }
  "immutable"             { return IMMUTABLE; }
  "using"                 { return USING; }
  "for"                   { return FOR; }
  "struct"                { return STRUCT; }
  "modifier"              { return MODIFIER; }
  "virtual"               { return VIRTUAL; }
  "override"              { return OVERRIDE; }
  "external"              { return EXTERNAL; }
  "constructor"           { return CONSTRUCTOR; }
  "function"              { return FUNCTION; }
  "returns"               { return RETURNS; }
  "fallback"              { return FALLBACK; }
  "receive"               { return RECEIVE; }
  "event"                 { return EVENT; }
  "anonymous"             { return ANONYMOUS; }
  "enum"                  { return ENUM; }
  "type"                  { return TYPE; }
  "indexed"               { return INDEXED; }
  "var"                   { return VAR; }
  "mapping"               { return MAPPING; }
  "memory"                { return MEMORY; }
  "storage"               { return STORAGE; }
  "calldata"              { return CALLDATA; }
  "pure"                  { return PURE; }
  "view"                  { return VIEW; }
  "unchecked"             { return UNCHECKED; }
  "return"                { return RETURN; }
  "if"                    { return IF; }
  "else"                  { return ELSE; }
  "try"                   { return TRY; }
  "catch"                 { return CATCH; }
  "while"                 { return WHILE; }
  "assembly"              { return ASSEMBLY; }
  "do"                    { return DO; }
  "continue"              { return CONTINUE; }
  "break"                 { return BREAK; }
  "throw"                 { return THROW; }
  "emit"                  { return EMIT; }
  "delete"                { return DELETE; }
  "new"                   { return NEW; }
  "wei"                   { return WEI; }
  "gwei"                  { return GWEI; }
  "ether"                 { return ETHER; }
  "seconds"               { return SECONDS; }
  "minutes"               { return MINUTES; }
  "hours"                 { return HOURS; }
  "days"                  { return DAYS; }
  "weeks"                 { return WEEKS; }
  "years"                 { return YEARS; }
  "address"               { return ADDRESS; }
  "payable"               { return PAYABLE; }
  "string"                { return STRING; }
  "bool"                  { return BOOL; }

  {HEXLITERAL}            { return HEXLITERAL; }
  {STRINGLITERAL}         { return STRINGLITERAL; }
  {UNTERMINATED_STRINGLITERAL} { return STRINGLITERAL; }
  {DECIMALNUMBER}         { return DECIMALNUMBER; }
  {FIXEDNUMBER}           { return FIXEDNUMBER; }
  {SCIENTIFICNUMBER}      { return SCIENTIFICNUMBER; }
  {HEXNUMBER}             { return HEXNUMBER; }
  {NUMBERUNIT}            { return NUMBERUNIT; }
  {INTNUMTYPE}            { return INTNUMTYPE; }
  {UINTNUMTYPE}           { return UINTNUMTYPE; }
  {BYTENUMTYPE}           { return BYTENUMTYPE; }
  {BYTESNUMTYPE}          { return BYTESNUMTYPE; }
  {FIXEDNUMTYPE}          { return FIXEDNUMTYPE; }
  {UFIXEDNUMTYPE}         { return UFIXEDNUMTYPE; }
  {BOOLEANLITERAL}        { return BOOLEANLITERAL; }
  {SPACE}                 { return SPACE; }
  {IDENTIFIER}            { return IDENTIFIER; }
}

// nested block comments are not supported, so don't track the occurrences of /*
<IN_BLOCK_COMMENT> {
  // to avoid tokenizing by whitespaces, and only split the comment into parts if
  // NatSpec tags are included.
  // a tag starts after whitespace or the comment's own `/`/`*` (`///@notice`, ` *@dev`, `/**@title`), as in solc
  [\s*/]"@"              {
                            yypushback(1);
                            return COMMENT;
                          }

  "*/"                    {
                            // do not treat /*/ as a comment
                            if (zzCurrentPos - zzBlockCommentStarted >= 2) {
                                yybegin(YYINITIAL);
                                return COMMENT;
                            }
                          }

  {NAT_SPEC_TAG}         {
                            //
                            if (isNatSpecTagStart(yycharat(-1))) {
                                return NAT_SPEC_TAG;
                            }
                            return COMMENT;
                          }

  <<EOF>>                 {
                            yybegin(YYINITIAL);
                            return COMMENT;
                          }

  [^]                     { }
}

<IN_EOL_COMMENT> {
  // to avoid tokenizing by whitespaces, and only split the comment into parts if
  // NatSpec tags are included.
  // a tag starts after whitespace or the comment's own `/`/`*` (`///@notice`, ` *@dev`, `/**@title`), as in solc
  [\s*/]"@"              {
                            yypushback(1);
                            return COMMENT;
                          }

  {NAT_SPEC_TAG}          {
                              //
                              if (isNatSpecTagStart(yycharat(-1))) {
                                  return NAT_SPEC_TAG;
                              }
                              return COMMENT;
                          }

  {EOL}                   {
                            yybegin(YYINITIAL);
                            // do not include '\n' in the comment
                            yypushback(1);
                            return COMMENT;
                          }

  <<EOF>>                 {
                            yybegin(YYINITIAL);
                            return COMMENT;
                          }

  [^]                     { }
}

<PRAGMA_OPEN> {
  {WHITE_SPACE}           { return WHITE_SPACE; }

  {IDENTIFIER}            {
                            yybegin(PRAGMA_REST);
                            return IDENTIFIER;
                          }

  <<EOF>>                 { yybegin(YYINITIAL); }
}

<PRAGMA_REST> {
  {WHITE_SPACE}           { return WHITE_SPACE; }

  {PRAGMAALL}             {
                            yybegin(YYINITIAL);
                            return PRAGMAALL;
                          }
  <<EOF>>                 { yybegin(YYINITIAL); }
}

[^] { return BAD_CHARACTER; }
