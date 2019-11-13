// ANTLR GENERATED CODE: DO NOT EDIT
package org.elasticsearch.xpack.eql.parser;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
class EqlBaseParser extends Parser {
  static { RuntimeMetaData.checkVersion("4.5.3", RuntimeMetaData.VERSION); }

  protected static final DFA[] _decisionToDFA;
  protected static final PredictionContextCache _sharedContextCache =
    new PredictionContextCache();
  public static final int
    T__0=1, AND=2, ANY=3, ASC=4, BETWEEN=5, BY=6, CHILD=7, DESCENDANT=8, EVENT=9, 
    FALSE=10, IN=11, JOIN=12, MAXSPAN=13, NOT=14, NULL=15, OF=16, OR=17, SEQUENCE=18, 
    TRUE=19, UNTIL=20, WHERE=21, WITH=22, PIPE=23, COUNT=24, FILTER=25, HEAD=26, 
    SORT=27, TAIL=28, UNIQUE_COUNT=29, EQ=30, NEQ=31, LT=32, LTE=33, GT=34, 
    GTE=35, PLUS=36, MINUS=37, ASTERISK=38, SLASH=39, PERCENT=40, DOT=41, 
    LB=42, RB=43, LP=44, RP=45, STRING=46, INTEGER_VALUE=47, DECIMAL_VALUE=48, 
    IDENTIFIER=49, DIGIT_IDENTIFIER=50, QUOTED_IDENTIFIER=51, SIMPLE_COMMENT=52, 
    BRACKETED_COMMENT=53, WS=54, UNRECOGNIZED=55, DELIMITER=56;
  public static final int
    RULE_singleStatement = 0, RULE_singleExpression = 1, RULE_statement = 2, 
    RULE_sequence = 3, RULE_join = 4, RULE_joinKeys = 5, RULE_span = 6, RULE_match = 7, 
    RULE_query = 8, RULE_expression = 9, RULE_booleanExpression = 10, RULE_predicated = 11, 
    RULE_predicate = 12, RULE_valueExpression = 13, RULE_primaryExpression = 14, 
    RULE_functionExpression = 15, RULE_constant = 16, RULE_comparisonOperator = 17, 
    RULE_booleanValue = 18, RULE_identifier = 19, RULE_quoteIdentifier = 20, 
    RULE_unquoteIdentifier = 21, RULE_number = 22, RULE_string = 23;
  public static final String[] ruleNames = {
    "singleStatement", "singleExpression", "statement", "sequence", "join", 
    "joinKeys", "span", "match", "query", "expression", "booleanExpression", 
    "predicated", "predicate", "valueExpression", "primaryExpression", "functionExpression", 
    "constant", "comparisonOperator", "booleanValue", "identifier", "quoteIdentifier", 
    "unquoteIdentifier", "number", "string"
  };

  private static final String[] _LITERAL_NAMES = {
    null, "','", "'AND'", "'ANY'", "'ASC'", "'BETWEEN'", "'BY'", "'CHILD'", 
    "'DESCENDANT'", "'EVENT'", "'FALSE'", "'IN'", "'JOIN'", "'MAXSPAN'", "'NOT'", 
    "'NULL'", "'OF'", "'OR'", "'SEQUENCE'", "'TRUE'", "'UNTIL'", "'WHERE'", 
    "'WITH'", "'|'", "'COUNT'", "'FILTER'", "'HEAD'", "'SORT'", "'TAIL'", 
    "'UNIQUE_COUNT'", null, null, "'<'", "'<='", "'>'", "'>='", "'+'", "'-'", 
    "'*'", "'/'", "'%'", "'.'", "'['", "']'", "'('", "')'"
  };
  private static final String[] _SYMBOLIC_NAMES = {
    null, null, "AND", "ANY", "ASC", "BETWEEN", "BY", "CHILD", "DESCENDANT", 
    "EVENT", "FALSE", "IN", "JOIN", "MAXSPAN", "NOT", "NULL", "OF", "OR", 
    "SEQUENCE", "TRUE", "UNTIL", "WHERE", "WITH", "PIPE", "COUNT", "FILTER", 
    "HEAD", "SORT", "TAIL", "UNIQUE_COUNT", "EQ", "NEQ", "LT", "LTE", "GT", 
    "GTE", "PLUS", "MINUS", "ASTERISK", "SLASH", "PERCENT", "DOT", "LB", "RB", 
    "LP", "RP", "STRING", "INTEGER_VALUE", "DECIMAL_VALUE", "IDENTIFIER", 
    "DIGIT_IDENTIFIER", "QUOTED_IDENTIFIER", "SIMPLE_COMMENT", "BRACKETED_COMMENT", 
    "WS", "UNRECOGNIZED", "DELIMITER"
  };
  public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

  /**
   * @deprecated Use {@link #VOCABULARY} instead.
   */
  @Deprecated
  public static final String[] tokenNames;
  static {
    tokenNames = new String[_SYMBOLIC_NAMES.length];
    for (int i = 0; i < tokenNames.length; i++) {
      tokenNames[i] = VOCABULARY.getLiteralName(i);
      if (tokenNames[i] == null) {
        tokenNames[i] = VOCABULARY.getSymbolicName(i);
      }

      if (tokenNames[i] == null) {
        tokenNames[i] = "<INVALID>";
      }
    }
  }

  @Override
  @Deprecated
  public String[] getTokenNames() {
    return tokenNames;
  }

  @Override

  public Vocabulary getVocabulary() {
    return VOCABULARY;
  }

  @Override
  public String getGrammarFileName() { return "EqlBase.g4"; }

  @Override
  public String[] getRuleNames() { return ruleNames; }

  @Override
  public String getSerializedATN() { return _serializedATN; }

  @Override
  public ATN getATN() { return _ATN; }

  public EqlBaseParser(TokenStream input) {
    super(input);
    _interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
  }
  public static class SingleStatementContext extends ParserRuleContext {
    public StatementContext statement() {
      return getRuleContext(StatementContext.class,0);
    }
    public TerminalNode EOF() { return getToken(EqlBaseParser.EOF, 0); }
    public SingleStatementContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_singleStatement; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterSingleStatement(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitSingleStatement(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitSingleStatement(this);
      else return visitor.visitChildren(this);
    }
  }

  public final SingleStatementContext singleStatement() throws RecognitionException {
    SingleStatementContext _localctx = new SingleStatementContext(_ctx, getState());
    enterRule(_localctx, 0, RULE_singleStatement);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(48);
      statement();
      setState(49);
      match(EOF);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class SingleExpressionContext extends ParserRuleContext {
    public ExpressionContext expression() {
      return getRuleContext(ExpressionContext.class,0);
    }
    public TerminalNode EOF() { return getToken(EqlBaseParser.EOF, 0); }
    public SingleExpressionContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_singleExpression; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterSingleExpression(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitSingleExpression(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitSingleExpression(this);
      else return visitor.visitChildren(this);
    }
  }

  public final SingleExpressionContext singleExpression() throws RecognitionException {
    SingleExpressionContext _localctx = new SingleExpressionContext(_ctx, getState());
    enterRule(_localctx, 2, RULE_singleExpression);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(51);
      expression();
      setState(52);
      match(EOF);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class StatementContext extends ParserRuleContext {
    public SequenceContext sequence() {
      return getRuleContext(SequenceContext.class,0);
    }
    public JoinContext join() {
      return getRuleContext(JoinContext.class,0);
    }
    public QueryContext query() {
      return getRuleContext(QueryContext.class,0);
    }
    public StatementContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_statement; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterStatement(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitStatement(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitStatement(this);
      else return visitor.visitChildren(this);
    }
  }

  public final StatementContext statement() throws RecognitionException {
    StatementContext _localctx = new StatementContext(_ctx, getState());
    enterRule(_localctx, 4, RULE_statement);
    try {
      setState(57);
      switch (_input.LA(1)) {
      case SEQUENCE:
        enterOuterAlt(_localctx, 1);
        {
        setState(54);
        sequence();
        }
        break;
      case JOIN:
        enterOuterAlt(_localctx, 2);
        {
        setState(55);
        join();
        }
        break;
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
      case QUOTED_IDENTIFIER:
        enterOuterAlt(_localctx, 3);
        {
        setState(56);
        query();
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class SequenceContext extends ParserRuleContext {
    public JoinKeysContext by;
    public TerminalNode SEQUENCE() { return getToken(EqlBaseParser.SEQUENCE, 0); }
    public SpanContext span() {
      return getRuleContext(SpanContext.class,0);
    }
    public List<MatchContext> match() {
      return getRuleContexts(MatchContext.class);
    }
    public MatchContext match(int i) {
      return getRuleContext(MatchContext.class,i);
    }
    public TerminalNode UNTIL() { return getToken(EqlBaseParser.UNTIL, 0); }
    public JoinKeysContext joinKeys() {
      return getRuleContext(JoinKeysContext.class,0);
    }
    public SequenceContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_sequence; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterSequence(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitSequence(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitSequence(this);
      else return visitor.visitChildren(this);
    }
  }

  public final SequenceContext sequence() throws RecognitionException {
    SequenceContext _localctx = new SequenceContext(_ctx, getState());
    enterRule(_localctx, 6, RULE_sequence);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(59);
      match(SEQUENCE);
      setState(61);
      _la = _input.LA(1);
      if (_la==BY) {
        {
        setState(60);
        ((SequenceContext)_localctx).by = joinKeys();
        }
      }

      setState(64);
      _la = _input.LA(1);
      if (_la==WITH) {
        {
        setState(63);
        span();
        }
      }

      setState(67); 
      _errHandler.sync(this);
      _la = _input.LA(1);
      do {
        {
        {
        setState(66);
        match();
        }
        }
        setState(69); 
        _errHandler.sync(this);
        _la = _input.LA(1);
      } while ( _la==LB );
      setState(73);
      _la = _input.LA(1);
      if (_la==UNTIL) {
        {
        setState(71);
        match(UNTIL);
        setState(72);
        match();
        }
      }

      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class JoinContext extends ParserRuleContext {
    public JoinKeysContext by;
    public TerminalNode JOIN() { return getToken(EqlBaseParser.JOIN, 0); }
    public List<MatchContext> match() {
      return getRuleContexts(MatchContext.class);
    }
    public MatchContext match(int i) {
      return getRuleContext(MatchContext.class,i);
    }
    public TerminalNode UNTIL() { return getToken(EqlBaseParser.UNTIL, 0); }
    public JoinKeysContext joinKeys() {
      return getRuleContext(JoinKeysContext.class,0);
    }
    public JoinContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_join; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterJoin(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitJoin(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitJoin(this);
      else return visitor.visitChildren(this);
    }
  }

  public final JoinContext join() throws RecognitionException {
    JoinContext _localctx = new JoinContext(_ctx, getState());
    enterRule(_localctx, 8, RULE_join);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(75);
      match(JOIN);
      setState(77);
      _la = _input.LA(1);
      if (_la==BY) {
        {
        setState(76);
        ((JoinContext)_localctx).by = joinKeys();
        }
      }

      setState(80); 
      _errHandler.sync(this);
      _la = _input.LA(1);
      do {
        {
        {
        setState(79);
        match();
        }
        }
        setState(82); 
        _errHandler.sync(this);
        _la = _input.LA(1);
      } while ( _la==LB );
      setState(86);
      _la = _input.LA(1);
      if (_la==UNTIL) {
        {
        setState(84);
        match(UNTIL);
        setState(85);
        match();
        }
      }

      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class JoinKeysContext extends ParserRuleContext {
    public TerminalNode BY() { return getToken(EqlBaseParser.BY, 0); }
    public List<IdentifierContext> identifier() {
      return getRuleContexts(IdentifierContext.class);
    }
    public IdentifierContext identifier(int i) {
      return getRuleContext(IdentifierContext.class,i);
    }
    public JoinKeysContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_joinKeys; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterJoinKeys(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitJoinKeys(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitJoinKeys(this);
      else return visitor.visitChildren(this);
    }
  }

  public final JoinKeysContext joinKeys() throws RecognitionException {
    JoinKeysContext _localctx = new JoinKeysContext(_ctx, getState());
    enterRule(_localctx, 10, RULE_joinKeys);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(88);
      match(BY);
      setState(89);
      identifier();
      setState(94);
      _errHandler.sync(this);
      _la = _input.LA(1);
      while (_la==T__0) {
        {
        {
        setState(90);
        match(T__0);
        setState(91);
        identifier();
        }
        }
        setState(96);
        _errHandler.sync(this);
        _la = _input.LA(1);
      }
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class SpanContext extends ParserRuleContext {
    public StringContext unit;
    public TerminalNode WITH() { return getToken(EqlBaseParser.WITH, 0); }
    public TerminalNode MAXSPAN() { return getToken(EqlBaseParser.MAXSPAN, 0); }
    public TerminalNode EQ() { return getToken(EqlBaseParser.EQ, 0); }
    public NumberContext number() {
      return getRuleContext(NumberContext.class,0);
    }
    public StringContext string() {
      return getRuleContext(StringContext.class,0);
    }
    public SpanContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_span; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterSpan(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitSpan(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitSpan(this);
      else return visitor.visitChildren(this);
    }
  }

  public final SpanContext span() throws RecognitionException {
    SpanContext _localctx = new SpanContext(_ctx, getState());
    enterRule(_localctx, 12, RULE_span);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(97);
      match(WITH);
      setState(98);
      match(MAXSPAN);
      setState(99);
      match(EQ);
      setState(100);
      number();
      setState(102);
      _la = _input.LA(1);
      if (_la==STRING) {
        {
        setState(101);
        ((SpanContext)_localctx).unit = string();
        }
      }

      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class MatchContext extends ParserRuleContext {
    public TerminalNode LB() { return getToken(EqlBaseParser.LB, 0); }
    public QueryContext query() {
      return getRuleContext(QueryContext.class,0);
    }
    public TerminalNode RB() { return getToken(EqlBaseParser.RB, 0); }
    public MatchContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_match; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterMatch(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitMatch(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitMatch(this);
      else return visitor.visitChildren(this);
    }
  }

  public final MatchContext match() throws RecognitionException {
    MatchContext _localctx = new MatchContext(_ctx, getState());
    enterRule(_localctx, 14, RULE_match);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(104);
      match(LB);
      setState(105);
      query();
      setState(106);
      match(RB);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class QueryContext extends ParserRuleContext {
    public IdentifierContext event;
    public TerminalNode WHERE() { return getToken(EqlBaseParser.WHERE, 0); }
    public ExpressionContext expression() {
      return getRuleContext(ExpressionContext.class,0);
    }
    public IdentifierContext identifier() {
      return getRuleContext(IdentifierContext.class,0);
    }
    public QueryContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_query; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterQuery(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitQuery(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitQuery(this);
      else return visitor.visitChildren(this);
    }
  }

  public final QueryContext query() throws RecognitionException {
    QueryContext _localctx = new QueryContext(_ctx, getState());
    enterRule(_localctx, 16, RULE_query);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(108);
      ((QueryContext)_localctx).event = identifier();
      setState(109);
      match(WHERE);
      setState(110);
      expression();
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class ExpressionContext extends ParserRuleContext {
    public BooleanExpressionContext booleanExpression() {
      return getRuleContext(BooleanExpressionContext.class,0);
    }
    public ExpressionContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_expression; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterExpression(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitExpression(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitExpression(this);
      else return visitor.visitChildren(this);
    }
  }

  public final ExpressionContext expression() throws RecognitionException {
    ExpressionContext _localctx = new ExpressionContext(_ctx, getState());
    enterRule(_localctx, 18, RULE_expression);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(112);
      booleanExpression(0);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class BooleanExpressionContext extends ParserRuleContext {
    public BooleanExpressionContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_booleanExpression; }
   
    public BooleanExpressionContext() { }
    public void copyFrom(BooleanExpressionContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class LogicalNotContext extends BooleanExpressionContext {
    public TerminalNode NOT() { return getToken(EqlBaseParser.NOT, 0); }
    public BooleanExpressionContext booleanExpression() {
      return getRuleContext(BooleanExpressionContext.class,0);
    }
    public LogicalNotContext(BooleanExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterLogicalNot(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitLogicalNot(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitLogicalNot(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class BooleanDefaultContext extends BooleanExpressionContext {
    public PredicatedContext predicated() {
      return getRuleContext(PredicatedContext.class,0);
    }
    public BooleanDefaultContext(BooleanExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterBooleanDefault(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitBooleanDefault(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitBooleanDefault(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class LogicalBinaryContext extends BooleanExpressionContext {
    public BooleanExpressionContext left;
    public Token operator;
    public BooleanExpressionContext right;
    public List<BooleanExpressionContext> booleanExpression() {
      return getRuleContexts(BooleanExpressionContext.class);
    }
    public BooleanExpressionContext booleanExpression(int i) {
      return getRuleContext(BooleanExpressionContext.class,i);
    }
    public TerminalNode AND() { return getToken(EqlBaseParser.AND, 0); }
    public TerminalNode OR() { return getToken(EqlBaseParser.OR, 0); }
    public LogicalBinaryContext(BooleanExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterLogicalBinary(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitLogicalBinary(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitLogicalBinary(this);
      else return visitor.visitChildren(this);
    }
  }

  public final BooleanExpressionContext booleanExpression() throws RecognitionException {
    return booleanExpression(0);
  }

  private BooleanExpressionContext booleanExpression(int _p) throws RecognitionException {
    ParserRuleContext _parentctx = _ctx;
    int _parentState = getState();
    BooleanExpressionContext _localctx = new BooleanExpressionContext(_ctx, _parentState);
    BooleanExpressionContext _prevctx = _localctx;
    int _startState = 20;
    enterRecursionRule(_localctx, 20, RULE_booleanExpression, _p);
    try {
      int _alt;
      enterOuterAlt(_localctx, 1);
      {
      setState(118);
      switch (_input.LA(1)) {
      case NOT:
        {
        _localctx = new LogicalNotContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;

        setState(115);
        match(NOT);
        setState(116);
        booleanExpression(4);
        }
        break;
      case FALSE:
      case NULL:
      case TRUE:
      case PLUS:
      case MINUS:
      case LP:
      case STRING:
      case INTEGER_VALUE:
      case DECIMAL_VALUE:
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
      case QUOTED_IDENTIFIER:
        {
        _localctx = new BooleanDefaultContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;
        setState(117);
        predicated();
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
      _ctx.stop = _input.LT(-1);
      setState(128);
      _errHandler.sync(this);
      _alt = getInterpreter().adaptivePredict(_input,12,_ctx);
      while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
        if ( _alt==1 ) {
          if ( _parseListeners!=null ) triggerExitRuleEvent();
          _prevctx = _localctx;
          {
          setState(126);
          _errHandler.sync(this);
          switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
          case 1:
            {
            _localctx = new LogicalBinaryContext(new BooleanExpressionContext(_parentctx, _parentState));
            ((LogicalBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_booleanExpression);
            setState(120);
            if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
            setState(121);
            ((LogicalBinaryContext)_localctx).operator = match(AND);
            setState(122);
            ((LogicalBinaryContext)_localctx).right = booleanExpression(3);
            }
            break;
          case 2:
            {
            _localctx = new LogicalBinaryContext(new BooleanExpressionContext(_parentctx, _parentState));
            ((LogicalBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_booleanExpression);
            setState(123);
            if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
            setState(124);
            ((LogicalBinaryContext)_localctx).operator = match(OR);
            setState(125);
            ((LogicalBinaryContext)_localctx).right = booleanExpression(2);
            }
            break;
          }
          } 
        }
        setState(130);
        _errHandler.sync(this);
        _alt = getInterpreter().adaptivePredict(_input,12,_ctx);
      }
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      unrollRecursionContexts(_parentctx);
    }
    return _localctx;
  }

  public static class PredicatedContext extends ParserRuleContext {
    public ValueExpressionContext valueExpression() {
      return getRuleContext(ValueExpressionContext.class,0);
    }
    public PredicateContext predicate() {
      return getRuleContext(PredicateContext.class,0);
    }
    public PredicatedContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_predicated; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterPredicated(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitPredicated(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitPredicated(this);
      else return visitor.visitChildren(this);
    }
  }

  public final PredicatedContext predicated() throws RecognitionException {
    PredicatedContext _localctx = new PredicatedContext(_ctx, getState());
    enterRule(_localctx, 22, RULE_predicated);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(131);
      valueExpression(0);
      setState(133);
      _errHandler.sync(this);
      switch ( getInterpreter().adaptivePredict(_input,13,_ctx) ) {
      case 1:
        {
        setState(132);
        predicate();
        }
        break;
      }
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class PredicateContext extends ParserRuleContext {
    public Token kind;
    public ValueExpressionContext lower;
    public ValueExpressionContext upper;
    public TerminalNode AND() { return getToken(EqlBaseParser.AND, 0); }
    public TerminalNode BETWEEN() { return getToken(EqlBaseParser.BETWEEN, 0); }
    public List<ValueExpressionContext> valueExpression() {
      return getRuleContexts(ValueExpressionContext.class);
    }
    public ValueExpressionContext valueExpression(int i) {
      return getRuleContext(ValueExpressionContext.class,i);
    }
    public TerminalNode NOT() { return getToken(EqlBaseParser.NOT, 0); }
    public TerminalNode LP() { return getToken(EqlBaseParser.LP, 0); }
    public TerminalNode RP() { return getToken(EqlBaseParser.RP, 0); }
    public TerminalNode IN() { return getToken(EqlBaseParser.IN, 0); }
    public QueryContext query() {
      return getRuleContext(QueryContext.class,0);
    }
    public PredicateContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_predicate; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterPredicate(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitPredicate(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitPredicate(this);
      else return visitor.visitChildren(this);
    }
  }

  public final PredicateContext predicate() throws RecognitionException {
    PredicateContext _localctx = new PredicateContext(_ctx, getState());
    enterRule(_localctx, 24, RULE_predicate);
    int _la;
    try {
      setState(166);
      _errHandler.sync(this);
      switch ( getInterpreter().adaptivePredict(_input,18,_ctx) ) {
      case 1:
        enterOuterAlt(_localctx, 1);
        {
        setState(136);
        _la = _input.LA(1);
        if (_la==NOT) {
          {
          setState(135);
          match(NOT);
          }
        }

        setState(138);
        ((PredicateContext)_localctx).kind = match(BETWEEN);
        setState(139);
        ((PredicateContext)_localctx).lower = valueExpression(0);
        setState(140);
        match(AND);
        setState(141);
        ((PredicateContext)_localctx).upper = valueExpression(0);
        }
        break;
      case 2:
        enterOuterAlt(_localctx, 2);
        {
        setState(144);
        _la = _input.LA(1);
        if (_la==NOT) {
          {
          setState(143);
          match(NOT);
          }
        }

        setState(146);
        ((PredicateContext)_localctx).kind = match(IN);
        setState(147);
        match(LP);
        setState(148);
        valueExpression(0);
        setState(153);
        _errHandler.sync(this);
        _la = _input.LA(1);
        while (_la==T__0) {
          {
          {
          setState(149);
          match(T__0);
          setState(150);
          valueExpression(0);
          }
          }
          setState(155);
          _errHandler.sync(this);
          _la = _input.LA(1);
        }
        setState(156);
        match(RP);
        }
        break;
      case 3:
        enterOuterAlt(_localctx, 3);
        {
        setState(159);
        _la = _input.LA(1);
        if (_la==NOT) {
          {
          setState(158);
          match(NOT);
          }
        }

        setState(161);
        ((PredicateContext)_localctx).kind = match(IN);
        setState(162);
        match(LP);
        setState(163);
        query();
        setState(164);
        match(RP);
        }
        break;
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class ValueExpressionContext extends ParserRuleContext {
    public ValueExpressionContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_valueExpression; }
   
    public ValueExpressionContext() { }
    public void copyFrom(ValueExpressionContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class ValueExpressionDefaultContext extends ValueExpressionContext {
    public PrimaryExpressionContext primaryExpression() {
      return getRuleContext(PrimaryExpressionContext.class,0);
    }
    public ValueExpressionDefaultContext(ValueExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterValueExpressionDefault(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitValueExpressionDefault(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitValueExpressionDefault(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class ComparisonContext extends ValueExpressionContext {
    public ValueExpressionContext left;
    public ValueExpressionContext right;
    public ComparisonOperatorContext comparisonOperator() {
      return getRuleContext(ComparisonOperatorContext.class,0);
    }
    public List<ValueExpressionContext> valueExpression() {
      return getRuleContexts(ValueExpressionContext.class);
    }
    public ValueExpressionContext valueExpression(int i) {
      return getRuleContext(ValueExpressionContext.class,i);
    }
    public ComparisonContext(ValueExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterComparison(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitComparison(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitComparison(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class ArithmeticBinaryContext extends ValueExpressionContext {
    public ValueExpressionContext left;
    public Token operator;
    public ValueExpressionContext right;
    public List<ValueExpressionContext> valueExpression() {
      return getRuleContexts(ValueExpressionContext.class);
    }
    public ValueExpressionContext valueExpression(int i) {
      return getRuleContext(ValueExpressionContext.class,i);
    }
    public TerminalNode ASTERISK() { return getToken(EqlBaseParser.ASTERISK, 0); }
    public TerminalNode SLASH() { return getToken(EqlBaseParser.SLASH, 0); }
    public TerminalNode PERCENT() { return getToken(EqlBaseParser.PERCENT, 0); }
    public TerminalNode PLUS() { return getToken(EqlBaseParser.PLUS, 0); }
    public TerminalNode MINUS() { return getToken(EqlBaseParser.MINUS, 0); }
    public ArithmeticBinaryContext(ValueExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterArithmeticBinary(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitArithmeticBinary(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitArithmeticBinary(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class ArithmeticUnaryContext extends ValueExpressionContext {
    public Token operator;
    public ValueExpressionContext valueExpression() {
      return getRuleContext(ValueExpressionContext.class,0);
    }
    public TerminalNode MINUS() { return getToken(EqlBaseParser.MINUS, 0); }
    public TerminalNode PLUS() { return getToken(EqlBaseParser.PLUS, 0); }
    public ArithmeticUnaryContext(ValueExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterArithmeticUnary(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitArithmeticUnary(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitArithmeticUnary(this);
      else return visitor.visitChildren(this);
    }
  }

  public final ValueExpressionContext valueExpression() throws RecognitionException {
    return valueExpression(0);
  }

  private ValueExpressionContext valueExpression(int _p) throws RecognitionException {
    ParserRuleContext _parentctx = _ctx;
    int _parentState = getState();
    ValueExpressionContext _localctx = new ValueExpressionContext(_ctx, _parentState);
    ValueExpressionContext _prevctx = _localctx;
    int _startState = 26;
    enterRecursionRule(_localctx, 26, RULE_valueExpression, _p);
    int _la;
    try {
      int _alt;
      enterOuterAlt(_localctx, 1);
      {
      setState(172);
      switch (_input.LA(1)) {
      case FALSE:
      case NULL:
      case TRUE:
      case LP:
      case STRING:
      case INTEGER_VALUE:
      case DECIMAL_VALUE:
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
      case QUOTED_IDENTIFIER:
        {
        _localctx = new ValueExpressionDefaultContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;

        setState(169);
        primaryExpression();
        }
        break;
      case PLUS:
      case MINUS:
        {
        _localctx = new ArithmeticUnaryContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;
        setState(170);
        ((ArithmeticUnaryContext)_localctx).operator = _input.LT(1);
        _la = _input.LA(1);
        if ( !(_la==PLUS || _la==MINUS) ) {
          ((ArithmeticUnaryContext)_localctx).operator = (Token)_errHandler.recoverInline(this);
        } else {
          consume();
        }
        setState(171);
        valueExpression(4);
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
      _ctx.stop = _input.LT(-1);
      setState(186);
      _errHandler.sync(this);
      _alt = getInterpreter().adaptivePredict(_input,21,_ctx);
      while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
        if ( _alt==1 ) {
          if ( _parseListeners!=null ) triggerExitRuleEvent();
          _prevctx = _localctx;
          {
          setState(184);
          _errHandler.sync(this);
          switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
          case 1:
            {
            _localctx = new ArithmeticBinaryContext(new ValueExpressionContext(_parentctx, _parentState));
            ((ArithmeticBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_valueExpression);
            setState(174);
            if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
            setState(175);
            ((ArithmeticBinaryContext)_localctx).operator = _input.LT(1);
            _la = _input.LA(1);
            if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << ASTERISK) | (1L << SLASH) | (1L << PERCENT))) != 0)) ) {
              ((ArithmeticBinaryContext)_localctx).operator = (Token)_errHandler.recoverInline(this);
            } else {
              consume();
            }
            setState(176);
            ((ArithmeticBinaryContext)_localctx).right = valueExpression(4);
            }
            break;
          case 2:
            {
            _localctx = new ArithmeticBinaryContext(new ValueExpressionContext(_parentctx, _parentState));
            ((ArithmeticBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_valueExpression);
            setState(177);
            if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
            setState(178);
            ((ArithmeticBinaryContext)_localctx).operator = _input.LT(1);
            _la = _input.LA(1);
            if ( !(_la==PLUS || _la==MINUS) ) {
              ((ArithmeticBinaryContext)_localctx).operator = (Token)_errHandler.recoverInline(this);
            } else {
              consume();
            }
            setState(179);
            ((ArithmeticBinaryContext)_localctx).right = valueExpression(3);
            }
            break;
          case 3:
            {
            _localctx = new ComparisonContext(new ValueExpressionContext(_parentctx, _parentState));
            ((ComparisonContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_valueExpression);
            setState(180);
            if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
            setState(181);
            comparisonOperator();
            setState(182);
            ((ComparisonContext)_localctx).right = valueExpression(2);
            }
            break;
          }
          } 
        }
        setState(188);
        _errHandler.sync(this);
        _alt = getInterpreter().adaptivePredict(_input,21,_ctx);
      }
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      unrollRecursionContexts(_parentctx);
    }
    return _localctx;
  }

  public static class PrimaryExpressionContext extends ParserRuleContext {
    public PrimaryExpressionContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_primaryExpression; }
   
    public PrimaryExpressionContext() { }
    public void copyFrom(PrimaryExpressionContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class ConstantDefaultContext extends PrimaryExpressionContext {
    public ConstantContext constant() {
      return getRuleContext(ConstantContext.class,0);
    }
    public ConstantDefaultContext(PrimaryExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterConstantDefault(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitConstantDefault(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitConstantDefault(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class ParenthesizedExpressionContext extends PrimaryExpressionContext {
    public ExpressionContext expression() {
      return getRuleContext(ExpressionContext.class,0);
    }
    public ParenthesizedExpressionContext(PrimaryExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterParenthesizedExpression(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitParenthesizedExpression(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitParenthesizedExpression(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class FunctionContext extends PrimaryExpressionContext {
    public FunctionExpressionContext functionExpression() {
      return getRuleContext(FunctionExpressionContext.class,0);
    }
    public FunctionContext(PrimaryExpressionContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterFunction(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitFunction(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitFunction(this);
      else return visitor.visitChildren(this);
    }
  }

  public final PrimaryExpressionContext primaryExpression() throws RecognitionException {
    PrimaryExpressionContext _localctx = new PrimaryExpressionContext(_ctx, getState());
    enterRule(_localctx, 28, RULE_primaryExpression);
    try {
      setState(195);
      switch (_input.LA(1)) {
      case FALSE:
      case NULL:
      case TRUE:
      case STRING:
      case INTEGER_VALUE:
      case DECIMAL_VALUE:
        _localctx = new ConstantDefaultContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(189);
        constant();
        }
        break;
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
      case QUOTED_IDENTIFIER:
        _localctx = new FunctionContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(190);
        functionExpression();
        }
        break;
      case LP:
        _localctx = new ParenthesizedExpressionContext(_localctx);
        enterOuterAlt(_localctx, 3);
        {
        setState(191);
        match(LP);
        setState(192);
        expression();
        setState(193);
        match(RP);
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class FunctionExpressionContext extends ParserRuleContext {
    public IdentifierContext identifier() {
      return getRuleContext(IdentifierContext.class,0);
    }
    public TerminalNode LP() { return getToken(EqlBaseParser.LP, 0); }
    public TerminalNode RP() { return getToken(EqlBaseParser.RP, 0); }
    public List<ExpressionContext> expression() {
      return getRuleContexts(ExpressionContext.class);
    }
    public ExpressionContext expression(int i) {
      return getRuleContext(ExpressionContext.class,i);
    }
    public FunctionExpressionContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_functionExpression; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterFunctionExpression(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitFunctionExpression(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitFunctionExpression(this);
      else return visitor.visitChildren(this);
    }
  }

  public final FunctionExpressionContext functionExpression() throws RecognitionException {
    FunctionExpressionContext _localctx = new FunctionExpressionContext(_ctx, getState());
    enterRule(_localctx, 30, RULE_functionExpression);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(197);
      identifier();
      setState(198);
      match(LP);
      setState(207);
      _la = _input.LA(1);
      if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << FALSE) | (1L << NOT) | (1L << NULL) | (1L << TRUE) | (1L << PLUS) | (1L << MINUS) | (1L << LP) | (1L << STRING) | (1L << INTEGER_VALUE) | (1L << DECIMAL_VALUE) | (1L << IDENTIFIER) | (1L << DIGIT_IDENTIFIER) | (1L << QUOTED_IDENTIFIER))) != 0)) {
        {
        setState(199);
        expression();
        setState(204);
        _errHandler.sync(this);
        _la = _input.LA(1);
        while (_la==T__0) {
          {
          {
          setState(200);
          match(T__0);
          setState(201);
          expression();
          }
          }
          setState(206);
          _errHandler.sync(this);
          _la = _input.LA(1);
        }
        }
      }

      setState(209);
      match(RP);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class ConstantContext extends ParserRuleContext {
    public ConstantContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_constant; }
   
    public ConstantContext() { }
    public void copyFrom(ConstantContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class NullLiteralContext extends ConstantContext {
    public TerminalNode NULL() { return getToken(EqlBaseParser.NULL, 0); }
    public NullLiteralContext(ConstantContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterNullLiteral(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitNullLiteral(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitNullLiteral(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class StringLiteralContext extends ConstantContext {
    public List<TerminalNode> STRING() { return getTokens(EqlBaseParser.STRING); }
    public TerminalNode STRING(int i) {
      return getToken(EqlBaseParser.STRING, i);
    }
    public StringLiteralContext(ConstantContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterStringLiteral(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitStringLiteral(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitStringLiteral(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class NumericLiteralContext extends ConstantContext {
    public NumberContext number() {
      return getRuleContext(NumberContext.class,0);
    }
    public NumericLiteralContext(ConstantContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterNumericLiteral(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitNumericLiteral(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitNumericLiteral(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class BooleanLiteralContext extends ConstantContext {
    public BooleanValueContext booleanValue() {
      return getRuleContext(BooleanValueContext.class,0);
    }
    public BooleanLiteralContext(ConstantContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterBooleanLiteral(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitBooleanLiteral(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitBooleanLiteral(this);
      else return visitor.visitChildren(this);
    }
  }

  public final ConstantContext constant() throws RecognitionException {
    ConstantContext _localctx = new ConstantContext(_ctx, getState());
    enterRule(_localctx, 32, RULE_constant);
    try {
      int _alt;
      setState(219);
      switch (_input.LA(1)) {
      case NULL:
        _localctx = new NullLiteralContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(211);
        match(NULL);
        }
        break;
      case INTEGER_VALUE:
      case DECIMAL_VALUE:
        _localctx = new NumericLiteralContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(212);
        number();
        }
        break;
      case FALSE:
      case TRUE:
        _localctx = new BooleanLiteralContext(_localctx);
        enterOuterAlt(_localctx, 3);
        {
        setState(213);
        booleanValue();
        }
        break;
      case STRING:
        _localctx = new StringLiteralContext(_localctx);
        enterOuterAlt(_localctx, 4);
        {
        setState(215); 
        _errHandler.sync(this);
        _alt = 1;
        do {
          switch (_alt) {
          case 1:
            {
            {
            setState(214);
            match(STRING);
            }
            }
            break;
          default:
            throw new NoViableAltException(this);
          }
          setState(217); 
          _errHandler.sync(this);
          _alt = getInterpreter().adaptivePredict(_input,25,_ctx);
        } while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class ComparisonOperatorContext extends ParserRuleContext {
    public TerminalNode EQ() { return getToken(EqlBaseParser.EQ, 0); }
    public TerminalNode NEQ() { return getToken(EqlBaseParser.NEQ, 0); }
    public TerminalNode LT() { return getToken(EqlBaseParser.LT, 0); }
    public TerminalNode LTE() { return getToken(EqlBaseParser.LTE, 0); }
    public TerminalNode GT() { return getToken(EqlBaseParser.GT, 0); }
    public TerminalNode GTE() { return getToken(EqlBaseParser.GTE, 0); }
    public ComparisonOperatorContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_comparisonOperator; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterComparisonOperator(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitComparisonOperator(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitComparisonOperator(this);
      else return visitor.visitChildren(this);
    }
  }

  public final ComparisonOperatorContext comparisonOperator() throws RecognitionException {
    ComparisonOperatorContext _localctx = new ComparisonOperatorContext(_ctx, getState());
    enterRule(_localctx, 34, RULE_comparisonOperator);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(221);
      _la = _input.LA(1);
      if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << EQ) | (1L << NEQ) | (1L << LT) | (1L << LTE) | (1L << GT) | (1L << GTE))) != 0)) ) {
      _errHandler.recoverInline(this);
      } else {
        consume();
      }
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class BooleanValueContext extends ParserRuleContext {
    public TerminalNode TRUE() { return getToken(EqlBaseParser.TRUE, 0); }
    public TerminalNode FALSE() { return getToken(EqlBaseParser.FALSE, 0); }
    public BooleanValueContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_booleanValue; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterBooleanValue(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitBooleanValue(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitBooleanValue(this);
      else return visitor.visitChildren(this);
    }
  }

  public final BooleanValueContext booleanValue() throws RecognitionException {
    BooleanValueContext _localctx = new BooleanValueContext(_ctx, getState());
    enterRule(_localctx, 36, RULE_booleanValue);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(223);
      _la = _input.LA(1);
      if ( !(_la==FALSE || _la==TRUE) ) {
      _errHandler.recoverInline(this);
      } else {
        consume();
      }
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class IdentifierContext extends ParserRuleContext {
    public QuoteIdentifierContext quoteIdentifier() {
      return getRuleContext(QuoteIdentifierContext.class,0);
    }
    public UnquoteIdentifierContext unquoteIdentifier() {
      return getRuleContext(UnquoteIdentifierContext.class,0);
    }
    public IdentifierContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_identifier; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterIdentifier(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitIdentifier(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitIdentifier(this);
      else return visitor.visitChildren(this);
    }
  }

  public final IdentifierContext identifier() throws RecognitionException {
    IdentifierContext _localctx = new IdentifierContext(_ctx, getState());
    enterRule(_localctx, 38, RULE_identifier);
    try {
      setState(227);
      switch (_input.LA(1)) {
      case QUOTED_IDENTIFIER:
        enterOuterAlt(_localctx, 1);
        {
        setState(225);
        quoteIdentifier();
        }
        break;
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
        enterOuterAlt(_localctx, 2);
        {
        setState(226);
        unquoteIdentifier();
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class QuoteIdentifierContext extends ParserRuleContext {
    public QuoteIdentifierContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_quoteIdentifier; }
   
    public QuoteIdentifierContext() { }
    public void copyFrom(QuoteIdentifierContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class QuotedIdentifierContext extends QuoteIdentifierContext {
    public TerminalNode QUOTED_IDENTIFIER() { return getToken(EqlBaseParser.QUOTED_IDENTIFIER, 0); }
    public QuotedIdentifierContext(QuoteIdentifierContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterQuotedIdentifier(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitQuotedIdentifier(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitQuotedIdentifier(this);
      else return visitor.visitChildren(this);
    }
  }

  public final QuoteIdentifierContext quoteIdentifier() throws RecognitionException {
    QuoteIdentifierContext _localctx = new QuoteIdentifierContext(_ctx, getState());
    enterRule(_localctx, 40, RULE_quoteIdentifier);
    try {
      _localctx = new QuotedIdentifierContext(_localctx);
      enterOuterAlt(_localctx, 1);
      {
      setState(229);
      match(QUOTED_IDENTIFIER);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class UnquoteIdentifierContext extends ParserRuleContext {
    public UnquoteIdentifierContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_unquoteIdentifier; }
   
    public UnquoteIdentifierContext() { }
    public void copyFrom(UnquoteIdentifierContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class DigitIdentifierContext extends UnquoteIdentifierContext {
    public TerminalNode DIGIT_IDENTIFIER() { return getToken(EqlBaseParser.DIGIT_IDENTIFIER, 0); }
    public DigitIdentifierContext(UnquoteIdentifierContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterDigitIdentifier(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitDigitIdentifier(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitDigitIdentifier(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class UnquotedIdentifierContext extends UnquoteIdentifierContext {
    public TerminalNode IDENTIFIER() { return getToken(EqlBaseParser.IDENTIFIER, 0); }
    public UnquotedIdentifierContext(UnquoteIdentifierContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterUnquotedIdentifier(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitUnquotedIdentifier(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitUnquotedIdentifier(this);
      else return visitor.visitChildren(this);
    }
  }

  public final UnquoteIdentifierContext unquoteIdentifier() throws RecognitionException {
    UnquoteIdentifierContext _localctx = new UnquoteIdentifierContext(_ctx, getState());
    enterRule(_localctx, 42, RULE_unquoteIdentifier);
    try {
      setState(233);
      switch (_input.LA(1)) {
      case IDENTIFIER:
        _localctx = new UnquotedIdentifierContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(231);
        match(IDENTIFIER);
        }
        break;
      case DIGIT_IDENTIFIER:
        _localctx = new DigitIdentifierContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(232);
        match(DIGIT_IDENTIFIER);
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class NumberContext extends ParserRuleContext {
    public NumberContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_number; }
   
    public NumberContext() { }
    public void copyFrom(NumberContext ctx) {
      super.copyFrom(ctx);
    }
  }
  public static class DecimalLiteralContext extends NumberContext {
    public TerminalNode DECIMAL_VALUE() { return getToken(EqlBaseParser.DECIMAL_VALUE, 0); }
    public DecimalLiteralContext(NumberContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterDecimalLiteral(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitDecimalLiteral(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitDecimalLiteral(this);
      else return visitor.visitChildren(this);
    }
  }
  public static class IntegerLiteralContext extends NumberContext {
    public TerminalNode INTEGER_VALUE() { return getToken(EqlBaseParser.INTEGER_VALUE, 0); }
    public IntegerLiteralContext(NumberContext ctx) { copyFrom(ctx); }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterIntegerLiteral(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitIntegerLiteral(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitIntegerLiteral(this);
      else return visitor.visitChildren(this);
    }
  }

  public final NumberContext number() throws RecognitionException {
    NumberContext _localctx = new NumberContext(_ctx, getState());
    enterRule(_localctx, 44, RULE_number);
    try {
      setState(237);
      switch (_input.LA(1)) {
      case DECIMAL_VALUE:
        _localctx = new DecimalLiteralContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(235);
        match(DECIMAL_VALUE);
        }
        break;
      case INTEGER_VALUE:
        _localctx = new IntegerLiteralContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(236);
        match(INTEGER_VALUE);
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public static class StringContext extends ParserRuleContext {
    public TerminalNode STRING() { return getToken(EqlBaseParser.STRING, 0); }
    public StringContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }
    @Override public int getRuleIndex() { return RULE_string; }
    @Override
    public void enterRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).enterString(this);
    }
    @Override
    public void exitRule(ParseTreeListener listener) {
      if ( listener instanceof EqlBaseListener ) ((EqlBaseListener)listener).exitString(this);
    }
    @Override
    public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
      if ( visitor instanceof EqlBaseVisitor ) return ((EqlBaseVisitor<? extends T>)visitor).visitString(this);
      else return visitor.visitChildren(this);
    }
  }

  public final StringContext string() throws RecognitionException {
    StringContext _localctx = new StringContext(_ctx, getState());
    enterRule(_localctx, 46, RULE_string);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(239);
      match(STRING);
      }
    }
    catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    }
    finally {
      exitRule();
    }
    return _localctx;
  }

  public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
    switch (ruleIndex) {
    case 10:
      return booleanExpression_sempred((BooleanExpressionContext)_localctx, predIndex);
    case 13:
      return valueExpression_sempred((ValueExpressionContext)_localctx, predIndex);
    }
    return true;
  }
  private boolean booleanExpression_sempred(BooleanExpressionContext _localctx, int predIndex) {
    switch (predIndex) {
    case 0:
      return precpred(_ctx, 2);
    case 1:
      return precpred(_ctx, 1);
    }
    return true;
  }
  private boolean valueExpression_sempred(ValueExpressionContext _localctx, int predIndex) {
    switch (predIndex) {
    case 2:
      return precpred(_ctx, 3);
    case 3:
      return precpred(_ctx, 2);
    case 4:
      return precpred(_ctx, 1);
    }
    return true;
  }

  public static final String _serializedATN =
    "\3\u0430\ud6d1\u8206\uad2d\u4417\uaef1\u8d80\uaadd\3:\u00f4\4\2\t\2\4"+
    "\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
    "\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
    "\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
    "\3\2\3\2\3\2\3\3\3\3\3\3\3\4\3\4\3\4\5\4<\n\4\3\5\3\5\5\5@\n\5\3\5\5\5"+
    "C\n\5\3\5\6\5F\n\5\r\5\16\5G\3\5\3\5\5\5L\n\5\3\6\3\6\5\6P\n\6\3\6\6\6"+
    "S\n\6\r\6\16\6T\3\6\3\6\5\6Y\n\6\3\7\3\7\3\7\3\7\7\7_\n\7\f\7\16\7b\13"+
    "\7\3\b\3\b\3\b\3\b\3\b\5\bi\n\b\3\t\3\t\3\t\3\t\3\n\3\n\3\n\3\n\3\13\3"+
    "\13\3\f\3\f\3\f\3\f\5\fy\n\f\3\f\3\f\3\f\3\f\3\f\3\f\7\f\u0081\n\f\f\f"+
    "\16\f\u0084\13\f\3\r\3\r\5\r\u0088\n\r\3\16\5\16\u008b\n\16\3\16\3\16"+
    "\3\16\3\16\3\16\3\16\5\16\u0093\n\16\3\16\3\16\3\16\3\16\3\16\7\16\u009a"+
    "\n\16\f\16\16\16\u009d\13\16\3\16\3\16\3\16\5\16\u00a2\n\16\3\16\3\16"+
    "\3\16\3\16\3\16\5\16\u00a9\n\16\3\17\3\17\3\17\3\17\5\17\u00af\n\17\3"+
    "\17\3\17\3\17\3\17\3\17\3\17\3\17\3\17\3\17\3\17\7\17\u00bb\n\17\f\17"+
    "\16\17\u00be\13\17\3\20\3\20\3\20\3\20\3\20\3\20\5\20\u00c6\n\20\3\21"+
    "\3\21\3\21\3\21\3\21\7\21\u00cd\n\21\f\21\16\21\u00d0\13\21\5\21\u00d2"+
    "\n\21\3\21\3\21\3\22\3\22\3\22\3\22\6\22\u00da\n\22\r\22\16\22\u00db\5"+
    "\22\u00de\n\22\3\23\3\23\3\24\3\24\3\25\3\25\5\25\u00e6\n\25\3\26\3\26"+
    "\3\27\3\27\5\27\u00ec\n\27\3\30\3\30\5\30\u00f0\n\30\3\31\3\31\3\31\2"+
    "\4\26\34\32\2\4\6\b\n\f\16\20\22\24\26\30\32\34\36 \"$&(*,.\60\2\6\3\2"+
    "&\'\3\2(*\3\2 %\4\2\f\f\25\25\u00ff\2\62\3\2\2\2\4\65\3\2\2\2\6;\3\2\2"+
    "\2\b=\3\2\2\2\nM\3\2\2\2\fZ\3\2\2\2\16c\3\2\2\2\20j\3\2\2\2\22n\3\2\2"+
    "\2\24r\3\2\2\2\26x\3\2\2\2\30\u0085\3\2\2\2\32\u00a8\3\2\2\2\34\u00ae"+
    "\3\2\2\2\36\u00c5\3\2\2\2 \u00c7\3\2\2\2\"\u00dd\3\2\2\2$\u00df\3\2\2"+
    "\2&\u00e1\3\2\2\2(\u00e5\3\2\2\2*\u00e7\3\2\2\2,\u00eb\3\2\2\2.\u00ef"+
    "\3\2\2\2\60\u00f1\3\2\2\2\62\63\5\6\4\2\63\64\7\2\2\3\64\3\3\2\2\2\65"+
    "\66\5\24\13\2\66\67\7\2\2\3\67\5\3\2\2\28<\5\b\5\29<\5\n\6\2:<\5\22\n"+
    "\2;8\3\2\2\2;9\3\2\2\2;:\3\2\2\2<\7\3\2\2\2=?\7\24\2\2>@\5\f\7\2?>\3\2"+
    "\2\2?@\3\2\2\2@B\3\2\2\2AC\5\16\b\2BA\3\2\2\2BC\3\2\2\2CE\3\2\2\2DF\5"+
    "\20\t\2ED\3\2\2\2FG\3\2\2\2GE\3\2\2\2GH\3\2\2\2HK\3\2\2\2IJ\7\26\2\2J"+
    "L\5\20\t\2KI\3\2\2\2KL\3\2\2\2L\t\3\2\2\2MO\7\16\2\2NP\5\f\7\2ON\3\2\2"+
    "\2OP\3\2\2\2PR\3\2\2\2QS\5\20\t\2RQ\3\2\2\2ST\3\2\2\2TR\3\2\2\2TU\3\2"+
    "\2\2UX\3\2\2\2VW\7\26\2\2WY\5\20\t\2XV\3\2\2\2XY\3\2\2\2Y\13\3\2\2\2Z"+
    "[\7\b\2\2[`\5(\25\2\\]\7\3\2\2]_\5(\25\2^\\\3\2\2\2_b\3\2\2\2`^\3\2\2"+
    "\2`a\3\2\2\2a\r\3\2\2\2b`\3\2\2\2cd\7\30\2\2de\7\17\2\2ef\7 \2\2fh\5."+
    "\30\2gi\5\60\31\2hg\3\2\2\2hi\3\2\2\2i\17\3\2\2\2jk\7,\2\2kl\5\22\n\2"+
    "lm\7-\2\2m\21\3\2\2\2no\5(\25\2op\7\27\2\2pq\5\24\13\2q\23\3\2\2\2rs\5"+
    "\26\f\2s\25\3\2\2\2tu\b\f\1\2uv\7\20\2\2vy\5\26\f\6wy\5\30\r\2xt\3\2\2"+
    "\2xw\3\2\2\2y\u0082\3\2\2\2z{\f\4\2\2{|\7\4\2\2|\u0081\5\26\f\5}~\f\3"+
    "\2\2~\177\7\23\2\2\177\u0081\5\26\f\4\u0080z\3\2\2\2\u0080}\3\2\2\2\u0081"+
    "\u0084\3\2\2\2\u0082\u0080\3\2\2\2\u0082\u0083\3\2\2\2\u0083\27\3\2\2"+
    "\2\u0084\u0082\3\2\2\2\u0085\u0087\5\34\17\2\u0086\u0088\5\32\16\2\u0087"+
    "\u0086\3\2\2\2\u0087\u0088\3\2\2\2\u0088\31\3\2\2\2\u0089\u008b\7\20\2"+
    "\2\u008a\u0089\3\2\2\2\u008a\u008b\3\2\2\2\u008b\u008c\3\2\2\2\u008c\u008d"+
    "\7\7\2\2\u008d\u008e\5\34\17\2\u008e\u008f\7\4\2\2\u008f\u0090\5\34\17"+
    "\2\u0090\u00a9\3\2\2\2\u0091\u0093\7\20\2\2\u0092\u0091\3\2\2\2\u0092"+
    "\u0093\3\2\2\2\u0093\u0094\3\2\2\2\u0094\u0095\7\r\2\2\u0095\u0096\7."+
    "\2\2\u0096\u009b\5\34\17\2\u0097\u0098\7\3\2\2\u0098\u009a\5\34\17\2\u0099"+
    "\u0097\3\2\2\2\u009a\u009d\3\2\2\2\u009b\u0099\3\2\2\2\u009b\u009c\3\2"+
    "\2\2\u009c\u009e\3\2\2\2\u009d\u009b\3\2\2\2\u009e\u009f\7/\2\2\u009f"+
    "\u00a9\3\2\2\2\u00a0\u00a2\7\20\2\2\u00a1\u00a0\3\2\2\2\u00a1\u00a2\3"+
    "\2\2\2\u00a2\u00a3\3\2\2\2\u00a3\u00a4\7\r\2\2\u00a4\u00a5\7.\2\2\u00a5"+
    "\u00a6\5\22\n\2\u00a6\u00a7\7/\2\2\u00a7\u00a9\3\2\2\2\u00a8\u008a\3\2"+
    "\2\2\u00a8\u0092\3\2\2\2\u00a8\u00a1\3\2\2\2\u00a9\33\3\2\2\2\u00aa\u00ab"+
    "\b\17\1\2\u00ab\u00af\5\36\20\2\u00ac\u00ad\t\2\2\2\u00ad\u00af\5\34\17"+
    "\6\u00ae\u00aa\3\2\2\2\u00ae\u00ac\3\2\2\2\u00af\u00bc\3\2\2\2\u00b0\u00b1"+
    "\f\5\2\2\u00b1\u00b2\t\3\2\2\u00b2\u00bb\5\34\17\6\u00b3\u00b4\f\4\2\2"+
    "\u00b4\u00b5\t\2\2\2\u00b5\u00bb\5\34\17\5\u00b6\u00b7\f\3\2\2\u00b7\u00b8"+
    "\5$\23\2\u00b8\u00b9\5\34\17\4\u00b9\u00bb\3\2\2\2\u00ba\u00b0\3\2\2\2"+
    "\u00ba\u00b3\3\2\2\2\u00ba\u00b6\3\2\2\2\u00bb\u00be\3\2\2\2\u00bc\u00ba"+
    "\3\2\2\2\u00bc\u00bd\3\2\2\2\u00bd\35\3\2\2\2\u00be\u00bc\3\2\2\2\u00bf"+
    "\u00c6\5\"\22\2\u00c0\u00c6\5 \21\2\u00c1\u00c2\7.\2\2\u00c2\u00c3\5\24"+
    "\13\2\u00c3\u00c4\7/\2\2\u00c4\u00c6\3\2\2\2\u00c5\u00bf\3\2\2\2\u00c5"+
    "\u00c0\3\2\2\2\u00c5\u00c1\3\2\2\2\u00c6\37\3\2\2\2\u00c7\u00c8\5(\25"+
    "\2\u00c8\u00d1\7.\2\2\u00c9\u00ce\5\24\13\2\u00ca\u00cb\7\3\2\2\u00cb"+
    "\u00cd\5\24\13\2\u00cc\u00ca\3\2\2\2\u00cd\u00d0\3\2\2\2\u00ce\u00cc\3"+
    "\2\2\2\u00ce\u00cf\3\2\2\2\u00cf\u00d2\3\2\2\2\u00d0\u00ce\3\2\2\2\u00d1"+
    "\u00c9\3\2\2\2\u00d1\u00d2\3\2\2\2\u00d2\u00d3\3\2\2\2\u00d3\u00d4\7/"+
    "\2\2\u00d4!\3\2\2\2\u00d5\u00de\7\21\2\2\u00d6\u00de\5.\30\2\u00d7\u00de"+
    "\5&\24\2\u00d8\u00da\7\60\2\2\u00d9\u00d8\3\2\2\2\u00da\u00db\3\2\2\2"+
    "\u00db\u00d9\3\2\2\2\u00db\u00dc\3\2\2\2\u00dc\u00de\3\2\2\2\u00dd\u00d5"+
    "\3\2\2\2\u00dd\u00d6\3\2\2\2\u00dd\u00d7\3\2\2\2\u00dd\u00d9\3\2\2\2\u00de"+
    "#\3\2\2\2\u00df\u00e0\t\4\2\2\u00e0%\3\2\2\2\u00e1\u00e2\t\5\2\2\u00e2"+
    "\'\3\2\2\2\u00e3\u00e6\5*\26\2\u00e4\u00e6\5,\27\2\u00e5\u00e3\3\2\2\2"+
    "\u00e5\u00e4\3\2\2\2\u00e6)\3\2\2\2\u00e7\u00e8\7\65\2\2\u00e8+\3\2\2"+
    "\2\u00e9\u00ec\7\63\2\2\u00ea\u00ec\7\64\2\2\u00eb\u00e9\3\2\2\2\u00eb"+
    "\u00ea\3\2\2\2\u00ec-\3\2\2\2\u00ed\u00f0\7\62\2\2\u00ee\u00f0\7\61\2"+
    "\2\u00ef\u00ed\3\2\2\2\u00ef\u00ee\3\2\2\2\u00f0/\3\2\2\2\u00f1\u00f2"+
    "\7\60\2\2\u00f2\61\3\2\2\2 ;?BGKOTX`hx\u0080\u0082\u0087\u008a\u0092\u009b"+
    "\u00a1\u00a8\u00ae\u00ba\u00bc\u00c5\u00ce\u00d1\u00db\u00dd\u00e5\u00eb"+
    "\u00ef";
  public static final ATN _ATN =
    new ATNDeserializer().deserialize(_serializedATN.toCharArray());
  static {
    _decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
    for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
      _decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
    }
  }
}
