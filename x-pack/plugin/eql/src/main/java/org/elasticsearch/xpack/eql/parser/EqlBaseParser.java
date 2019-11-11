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
    IDENTIFIER=49, DIGIT_IDENTIFIER=50, SIMPLE_COMMENT=51, BRACKETED_COMMENT=52, 
    WS=53, UNRECOGNIZED=54, DELIMITER=55;
  public static final int
    RULE_singleStatement = 0, RULE_singleExpression = 1, RULE_statement = 2, 
    RULE_sequence = 3, RULE_join = 4, RULE_joinKeys = 5, RULE_span = 6, RULE_match = 7, 
    RULE_query = 8, RULE_expression = 9, RULE_booleanExpression = 10, RULE_predicated = 11, 
    RULE_predicate = 12, RULE_valueExpression = 13, RULE_primaryExpression = 14, 
    RULE_functionExpression = 15, RULE_constant = 16, RULE_comparisonOperator = 17, 
    RULE_booleanValue = 18, RULE_identifier = 19, RULE_unquoteIdentifier = 20, 
    RULE_number = 21, RULE_string = 22;
  public static final String[] ruleNames = {
    "singleStatement", "singleExpression", "statement", "sequence", "join", 
    "joinKeys", "span", "match", "query", "expression", "booleanExpression", 
    "predicated", "predicate", "valueExpression", "primaryExpression", "functionExpression", 
    "constant", "comparisonOperator", "booleanValue", "identifier", "unquoteIdentifier", 
    "number", "string"
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
    "DIGIT_IDENTIFIER", "SIMPLE_COMMENT", "BRACKETED_COMMENT", "WS", "UNRECOGNIZED", 
    "DELIMITER"
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
      setState(46);
      statement();
      setState(47);
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
      setState(49);
      expression();
      setState(50);
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
      setState(55);
      switch (_input.LA(1)) {
      case SEQUENCE:
        enterOuterAlt(_localctx, 1);
        {
        setState(52);
        sequence();
        }
        break;
      case JOIN:
        enterOuterAlt(_localctx, 2);
        {
        setState(53);
        join();
        }
        break;
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
        enterOuterAlt(_localctx, 3);
        {
        setState(54);
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
      setState(57);
      match(SEQUENCE);
      setState(59);
      _la = _input.LA(1);
      if (_la==BY) {
        {
        setState(58);
        ((SequenceContext)_localctx).by = joinKeys();
        }
      }

      setState(62);
      _la = _input.LA(1);
      if (_la==WITH) {
        {
        setState(61);
        span();
        }
      }

      setState(65); 
      _errHandler.sync(this);
      _la = _input.LA(1);
      do {
        {
        {
        setState(64);
        match();
        }
        }
        setState(67); 
        _errHandler.sync(this);
        _la = _input.LA(1);
      } while ( _la==LB );
      setState(71);
      _la = _input.LA(1);
      if (_la==UNTIL) {
        {
        setState(69);
        match(UNTIL);
        setState(70);
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
      setState(73);
      match(JOIN);
      setState(75);
      _la = _input.LA(1);
      if (_la==BY) {
        {
        setState(74);
        ((JoinContext)_localctx).by = joinKeys();
        }
      }

      setState(78); 
      _errHandler.sync(this);
      _la = _input.LA(1);
      do {
        {
        {
        setState(77);
        match();
        }
        }
        setState(80); 
        _errHandler.sync(this);
        _la = _input.LA(1);
      } while ( _la==LB );
      setState(84);
      _la = _input.LA(1);
      if (_la==UNTIL) {
        {
        setState(82);
        match(UNTIL);
        setState(83);
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
      setState(86);
      match(BY);
      setState(87);
      identifier();
      setState(92);
      _errHandler.sync(this);
      _la = _input.LA(1);
      while (_la==T__0) {
        {
        {
        setState(88);
        match(T__0);
        setState(89);
        identifier();
        }
        }
        setState(94);
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
      setState(95);
      match(WITH);
      setState(96);
      match(MAXSPAN);
      setState(97);
      match(EQ);
      setState(98);
      number();
      setState(100);
      _la = _input.LA(1);
      if (_la==STRING) {
        {
        setState(99);
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
      setState(102);
      match(LB);
      setState(103);
      query();
      setState(104);
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
      setState(106);
      ((QueryContext)_localctx).event = identifier();
      setState(107);
      match(WHERE);
      setState(108);
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
      setState(110);
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
      setState(116);
      switch (_input.LA(1)) {
      case NOT:
        {
        _localctx = new LogicalNotContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;

        setState(113);
        match(NOT);
        setState(114);
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
        {
        _localctx = new BooleanDefaultContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;
        setState(115);
        predicated();
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
      _ctx.stop = _input.LT(-1);
      setState(126);
      _errHandler.sync(this);
      _alt = getInterpreter().adaptivePredict(_input,12,_ctx);
      while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
        if ( _alt==1 ) {
          if ( _parseListeners!=null ) triggerExitRuleEvent();
          _prevctx = _localctx;
          {
          setState(124);
          _errHandler.sync(this);
          switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
          case 1:
            {
            _localctx = new LogicalBinaryContext(new BooleanExpressionContext(_parentctx, _parentState));
            ((LogicalBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_booleanExpression);
            setState(118);
            if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
            setState(119);
            ((LogicalBinaryContext)_localctx).operator = match(AND);
            setState(120);
            ((LogicalBinaryContext)_localctx).right = booleanExpression(3);
            }
            break;
          case 2:
            {
            _localctx = new LogicalBinaryContext(new BooleanExpressionContext(_parentctx, _parentState));
            ((LogicalBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_booleanExpression);
            setState(121);
            if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
            setState(122);
            ((LogicalBinaryContext)_localctx).operator = match(OR);
            setState(123);
            ((LogicalBinaryContext)_localctx).right = booleanExpression(2);
            }
            break;
          }
          } 
        }
        setState(128);
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
      setState(129);
      valueExpression(0);
      setState(131);
      _errHandler.sync(this);
      switch ( getInterpreter().adaptivePredict(_input,13,_ctx) ) {
      case 1:
        {
        setState(130);
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
      setState(164);
      _errHandler.sync(this);
      switch ( getInterpreter().adaptivePredict(_input,18,_ctx) ) {
      case 1:
        enterOuterAlt(_localctx, 1);
        {
        setState(134);
        _la = _input.LA(1);
        if (_la==NOT) {
          {
          setState(133);
          match(NOT);
          }
        }

        setState(136);
        ((PredicateContext)_localctx).kind = match(BETWEEN);
        setState(137);
        ((PredicateContext)_localctx).lower = valueExpression(0);
        setState(138);
        match(AND);
        setState(139);
        ((PredicateContext)_localctx).upper = valueExpression(0);
        }
        break;
      case 2:
        enterOuterAlt(_localctx, 2);
        {
        setState(142);
        _la = _input.LA(1);
        if (_la==NOT) {
          {
          setState(141);
          match(NOT);
          }
        }

        setState(144);
        ((PredicateContext)_localctx).kind = match(IN);
        setState(145);
        match(LP);
        setState(146);
        valueExpression(0);
        setState(151);
        _errHandler.sync(this);
        _la = _input.LA(1);
        while (_la==T__0) {
          {
          {
          setState(147);
          match(T__0);
          setState(148);
          valueExpression(0);
          }
          }
          setState(153);
          _errHandler.sync(this);
          _la = _input.LA(1);
        }
        setState(154);
        match(RP);
        }
        break;
      case 3:
        enterOuterAlt(_localctx, 3);
        {
        setState(157);
        _la = _input.LA(1);
        if (_la==NOT) {
          {
          setState(156);
          match(NOT);
          }
        }

        setState(159);
        ((PredicateContext)_localctx).kind = match(IN);
        setState(160);
        match(LP);
        setState(161);
        query();
        setState(162);
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
      setState(170);
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
        {
        _localctx = new ValueExpressionDefaultContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;

        setState(167);
        primaryExpression();
        }
        break;
      case PLUS:
      case MINUS:
        {
        _localctx = new ArithmeticUnaryContext(_localctx);
        _ctx = _localctx;
        _prevctx = _localctx;
        setState(168);
        ((ArithmeticUnaryContext)_localctx).operator = _input.LT(1);
        _la = _input.LA(1);
        if ( !(_la==PLUS || _la==MINUS) ) {
          ((ArithmeticUnaryContext)_localctx).operator = (Token)_errHandler.recoverInline(this);
        } else {
          consume();
        }
        setState(169);
        valueExpression(4);
        }
        break;
      default:
        throw new NoViableAltException(this);
      }
      _ctx.stop = _input.LT(-1);
      setState(184);
      _errHandler.sync(this);
      _alt = getInterpreter().adaptivePredict(_input,21,_ctx);
      while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
        if ( _alt==1 ) {
          if ( _parseListeners!=null ) triggerExitRuleEvent();
          _prevctx = _localctx;
          {
          setState(182);
          _errHandler.sync(this);
          switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
          case 1:
            {
            _localctx = new ArithmeticBinaryContext(new ValueExpressionContext(_parentctx, _parentState));
            ((ArithmeticBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_valueExpression);
            setState(172);
            if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
            setState(173);
            ((ArithmeticBinaryContext)_localctx).operator = _input.LT(1);
            _la = _input.LA(1);
            if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << ASTERISK) | (1L << SLASH) | (1L << PERCENT))) != 0)) ) {
              ((ArithmeticBinaryContext)_localctx).operator = (Token)_errHandler.recoverInline(this);
            } else {
              consume();
            }
            setState(174);
            ((ArithmeticBinaryContext)_localctx).right = valueExpression(4);
            }
            break;
          case 2:
            {
            _localctx = new ArithmeticBinaryContext(new ValueExpressionContext(_parentctx, _parentState));
            ((ArithmeticBinaryContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_valueExpression);
            setState(175);
            if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
            setState(176);
            ((ArithmeticBinaryContext)_localctx).operator = _input.LT(1);
            _la = _input.LA(1);
            if ( !(_la==PLUS || _la==MINUS) ) {
              ((ArithmeticBinaryContext)_localctx).operator = (Token)_errHandler.recoverInline(this);
            } else {
              consume();
            }
            setState(177);
            ((ArithmeticBinaryContext)_localctx).right = valueExpression(3);
            }
            break;
          case 3:
            {
            _localctx = new ComparisonContext(new ValueExpressionContext(_parentctx, _parentState));
            ((ComparisonContext)_localctx).left = _prevctx;
            pushNewRecursionContext(_localctx, _startState, RULE_valueExpression);
            setState(178);
            if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
            setState(179);
            comparisonOperator();
            setState(180);
            ((ComparisonContext)_localctx).right = valueExpression(2);
            }
            break;
          }
          } 
        }
        setState(186);
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
      setState(193);
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
        setState(187);
        constant();
        }
        break;
      case IDENTIFIER:
      case DIGIT_IDENTIFIER:
        _localctx = new FunctionContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(188);
        functionExpression();
        }
        break;
      case LP:
        _localctx = new ParenthesizedExpressionContext(_localctx);
        enterOuterAlt(_localctx, 3);
        {
        setState(189);
        match(LP);
        setState(190);
        expression();
        setState(191);
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
      setState(195);
      identifier();
      setState(196);
      match(LP);
      setState(205);
      _la = _input.LA(1);
      if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << FALSE) | (1L << NOT) | (1L << NULL) | (1L << TRUE) | (1L << PLUS) | (1L << MINUS) | (1L << LP) | (1L << STRING) | (1L << INTEGER_VALUE) | (1L << DECIMAL_VALUE) | (1L << IDENTIFIER) | (1L << DIGIT_IDENTIFIER))) != 0)) {
        {
        setState(197);
        expression();
        setState(202);
        _errHandler.sync(this);
        _la = _input.LA(1);
        while (_la==T__0) {
          {
          {
          setState(198);
          match(T__0);
          setState(199);
          expression();
          }
          }
          setState(204);
          _errHandler.sync(this);
          _la = _input.LA(1);
        }
        }
      }

      setState(207);
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
      setState(217);
      switch (_input.LA(1)) {
      case NULL:
        _localctx = new NullLiteralContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(209);
        match(NULL);
        }
        break;
      case INTEGER_VALUE:
      case DECIMAL_VALUE:
        _localctx = new NumericLiteralContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(210);
        number();
        }
        break;
      case FALSE:
      case TRUE:
        _localctx = new BooleanLiteralContext(_localctx);
        enterOuterAlt(_localctx, 3);
        {
        setState(211);
        booleanValue();
        }
        break;
      case STRING:
        _localctx = new StringLiteralContext(_localctx);
        enterOuterAlt(_localctx, 4);
        {
        setState(213); 
        _errHandler.sync(this);
        _alt = 1;
        do {
          switch (_alt) {
          case 1:
            {
            {
            setState(212);
            match(STRING);
            }
            }
            break;
          default:
            throw new NoViableAltException(this);
          }
          setState(215); 
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
      setState(219);
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
      setState(221);
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
      enterOuterAlt(_localctx, 1);
      {
      setState(223);
      unquoteIdentifier();
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
    enterRule(_localctx, 40, RULE_unquoteIdentifier);
    try {
      setState(227);
      switch (_input.LA(1)) {
      case IDENTIFIER:
        _localctx = new UnquotedIdentifierContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(225);
        match(IDENTIFIER);
        }
        break;
      case DIGIT_IDENTIFIER:
        _localctx = new DigitIdentifierContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(226);
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
    enterRule(_localctx, 42, RULE_number);
    try {
      setState(231);
      switch (_input.LA(1)) {
      case DECIMAL_VALUE:
        _localctx = new DecimalLiteralContext(_localctx);
        enterOuterAlt(_localctx, 1);
        {
        setState(229);
        match(DECIMAL_VALUE);
        }
        break;
      case INTEGER_VALUE:
        _localctx = new IntegerLiteralContext(_localctx);
        enterOuterAlt(_localctx, 2);
        {
        setState(230);
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
    enterRule(_localctx, 44, RULE_string);
    try {
      enterOuterAlt(_localctx, 1);
      {
      setState(233);
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
    "\3\u0430\ud6d1\u8206\uad2d\u4417\uaef1\u8d80\uaadd\39\u00ee\4\2\t\2\4"+
    "\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
    "\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
    "\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\3\2\3\2\3"+
    "\2\3\3\3\3\3\3\3\4\3\4\3\4\5\4:\n\4\3\5\3\5\5\5>\n\5\3\5\5\5A\n\5\3\5"+
    "\6\5D\n\5\r\5\16\5E\3\5\3\5\5\5J\n\5\3\6\3\6\5\6N\n\6\3\6\6\6Q\n\6\r\6"+
    "\16\6R\3\6\3\6\5\6W\n\6\3\7\3\7\3\7\3\7\7\7]\n\7\f\7\16\7`\13\7\3\b\3"+
    "\b\3\b\3\b\3\b\5\bg\n\b\3\t\3\t\3\t\3\t\3\n\3\n\3\n\3\n\3\13\3\13\3\f"+
    "\3\f\3\f\3\f\5\fw\n\f\3\f\3\f\3\f\3\f\3\f\3\f\7\f\177\n\f\f\f\16\f\u0082"+
    "\13\f\3\r\3\r\5\r\u0086\n\r\3\16\5\16\u0089\n\16\3\16\3\16\3\16\3\16\3"+
    "\16\3\16\5\16\u0091\n\16\3\16\3\16\3\16\3\16\3\16\7\16\u0098\n\16\f\16"+
    "\16\16\u009b\13\16\3\16\3\16\3\16\5\16\u00a0\n\16\3\16\3\16\3\16\3\16"+
    "\3\16\5\16\u00a7\n\16\3\17\3\17\3\17\3\17\5\17\u00ad\n\17\3\17\3\17\3"+
    "\17\3\17\3\17\3\17\3\17\3\17\3\17\3\17\7\17\u00b9\n\17\f\17\16\17\u00bc"+
    "\13\17\3\20\3\20\3\20\3\20\3\20\3\20\5\20\u00c4\n\20\3\21\3\21\3\21\3"+
    "\21\3\21\7\21\u00cb\n\21\f\21\16\21\u00ce\13\21\5\21\u00d0\n\21\3\21\3"+
    "\21\3\22\3\22\3\22\3\22\6\22\u00d8\n\22\r\22\16\22\u00d9\5\22\u00dc\n"+
    "\22\3\23\3\23\3\24\3\24\3\25\3\25\3\26\3\26\5\26\u00e6\n\26\3\27\3\27"+
    "\5\27\u00ea\n\27\3\30\3\30\3\30\2\4\26\34\31\2\4\6\b\n\f\16\20\22\24\26"+
    "\30\32\34\36 \"$&(*,.\2\6\3\2&\'\3\2(*\3\2 %\4\2\f\f\25\25\u00f9\2\60"+
    "\3\2\2\2\4\63\3\2\2\2\69\3\2\2\2\b;\3\2\2\2\nK\3\2\2\2\fX\3\2\2\2\16a"+
    "\3\2\2\2\20h\3\2\2\2\22l\3\2\2\2\24p\3\2\2\2\26v\3\2\2\2\30\u0083\3\2"+
    "\2\2\32\u00a6\3\2\2\2\34\u00ac\3\2\2\2\36\u00c3\3\2\2\2 \u00c5\3\2\2\2"+
    "\"\u00db\3\2\2\2$\u00dd\3\2\2\2&\u00df\3\2\2\2(\u00e1\3\2\2\2*\u00e5\3"+
    "\2\2\2,\u00e9\3\2\2\2.\u00eb\3\2\2\2\60\61\5\6\4\2\61\62\7\2\2\3\62\3"+
    "\3\2\2\2\63\64\5\24\13\2\64\65\7\2\2\3\65\5\3\2\2\2\66:\5\b\5\2\67:\5"+
    "\n\6\28:\5\22\n\29\66\3\2\2\29\67\3\2\2\298\3\2\2\2:\7\3\2\2\2;=\7\24"+
    "\2\2<>\5\f\7\2=<\3\2\2\2=>\3\2\2\2>@\3\2\2\2?A\5\16\b\2@?\3\2\2\2@A\3"+
    "\2\2\2AC\3\2\2\2BD\5\20\t\2CB\3\2\2\2DE\3\2\2\2EC\3\2\2\2EF\3\2\2\2FI"+
    "\3\2\2\2GH\7\26\2\2HJ\5\20\t\2IG\3\2\2\2IJ\3\2\2\2J\t\3\2\2\2KM\7\16\2"+
    "\2LN\5\f\7\2ML\3\2\2\2MN\3\2\2\2NP\3\2\2\2OQ\5\20\t\2PO\3\2\2\2QR\3\2"+
    "\2\2RP\3\2\2\2RS\3\2\2\2SV\3\2\2\2TU\7\26\2\2UW\5\20\t\2VT\3\2\2\2VW\3"+
    "\2\2\2W\13\3\2\2\2XY\7\b\2\2Y^\5(\25\2Z[\7\3\2\2[]\5(\25\2\\Z\3\2\2\2"+
    "]`\3\2\2\2^\\\3\2\2\2^_\3\2\2\2_\r\3\2\2\2`^\3\2\2\2ab\7\30\2\2bc\7\17"+
    "\2\2cd\7 \2\2df\5,\27\2eg\5.\30\2fe\3\2\2\2fg\3\2\2\2g\17\3\2\2\2hi\7"+
    ",\2\2ij\5\22\n\2jk\7-\2\2k\21\3\2\2\2lm\5(\25\2mn\7\27\2\2no\5\24\13\2"+
    "o\23\3\2\2\2pq\5\26\f\2q\25\3\2\2\2rs\b\f\1\2st\7\20\2\2tw\5\26\f\6uw"+
    "\5\30\r\2vr\3\2\2\2vu\3\2\2\2w\u0080\3\2\2\2xy\f\4\2\2yz\7\4\2\2z\177"+
    "\5\26\f\5{|\f\3\2\2|}\7\23\2\2}\177\5\26\f\4~x\3\2\2\2~{\3\2\2\2\177\u0082"+
    "\3\2\2\2\u0080~\3\2\2\2\u0080\u0081\3\2\2\2\u0081\27\3\2\2\2\u0082\u0080"+
    "\3\2\2\2\u0083\u0085\5\34\17\2\u0084\u0086\5\32\16\2\u0085\u0084\3\2\2"+
    "\2\u0085\u0086\3\2\2\2\u0086\31\3\2\2\2\u0087\u0089\7\20\2\2\u0088\u0087"+
    "\3\2\2\2\u0088\u0089\3\2\2\2\u0089\u008a\3\2\2\2\u008a\u008b\7\7\2\2\u008b"+
    "\u008c\5\34\17\2\u008c\u008d\7\4\2\2\u008d\u008e\5\34\17\2\u008e\u00a7"+
    "\3\2\2\2\u008f\u0091\7\20\2\2\u0090\u008f\3\2\2\2\u0090\u0091\3\2\2\2"+
    "\u0091\u0092\3\2\2\2\u0092\u0093\7\r\2\2\u0093\u0094\7.\2\2\u0094\u0099"+
    "\5\34\17\2\u0095\u0096\7\3\2\2\u0096\u0098\5\34\17\2\u0097\u0095\3\2\2"+
    "\2\u0098\u009b\3\2\2\2\u0099\u0097\3\2\2\2\u0099\u009a\3\2\2\2\u009a\u009c"+
    "\3\2\2\2\u009b\u0099\3\2\2\2\u009c\u009d\7/\2\2\u009d\u00a7\3\2\2\2\u009e"+
    "\u00a0\7\20\2\2\u009f\u009e\3\2\2\2\u009f\u00a0\3\2\2\2\u00a0\u00a1\3"+
    "\2\2\2\u00a1\u00a2\7\r\2\2\u00a2\u00a3\7.\2\2\u00a3\u00a4\5\22\n\2\u00a4"+
    "\u00a5\7/\2\2\u00a5\u00a7\3\2\2\2\u00a6\u0088\3\2\2\2\u00a6\u0090\3\2"+
    "\2\2\u00a6\u009f\3\2\2\2\u00a7\33\3\2\2\2\u00a8\u00a9\b\17\1\2\u00a9\u00ad"+
    "\5\36\20\2\u00aa\u00ab\t\2\2\2\u00ab\u00ad\5\34\17\6\u00ac\u00a8\3\2\2"+
    "\2\u00ac\u00aa\3\2\2\2\u00ad\u00ba\3\2\2\2\u00ae\u00af\f\5\2\2\u00af\u00b0"+
    "\t\3\2\2\u00b0\u00b9\5\34\17\6\u00b1\u00b2\f\4\2\2\u00b2\u00b3\t\2\2\2"+
    "\u00b3\u00b9\5\34\17\5\u00b4\u00b5\f\3\2\2\u00b5\u00b6\5$\23\2\u00b6\u00b7"+
    "\5\34\17\4\u00b7\u00b9\3\2\2\2\u00b8\u00ae\3\2\2\2\u00b8\u00b1\3\2\2\2"+
    "\u00b8\u00b4\3\2\2\2\u00b9\u00bc\3\2\2\2\u00ba\u00b8\3\2\2\2\u00ba\u00bb"+
    "\3\2\2\2\u00bb\35\3\2\2\2\u00bc\u00ba\3\2\2\2\u00bd\u00c4\5\"\22\2\u00be"+
    "\u00c4\5 \21\2\u00bf\u00c0\7.\2\2\u00c0\u00c1\5\24\13\2\u00c1\u00c2\7"+
    "/\2\2\u00c2\u00c4\3\2\2\2\u00c3\u00bd\3\2\2\2\u00c3\u00be\3\2\2\2\u00c3"+
    "\u00bf\3\2\2\2\u00c4\37\3\2\2\2\u00c5\u00c6\5(\25\2\u00c6\u00cf\7.\2\2"+
    "\u00c7\u00cc\5\24\13\2\u00c8\u00c9\7\3\2\2\u00c9\u00cb\5\24\13\2\u00ca"+
    "\u00c8\3\2\2\2\u00cb\u00ce\3\2\2\2\u00cc\u00ca\3\2\2\2\u00cc\u00cd\3\2"+
    "\2\2\u00cd\u00d0\3\2\2\2\u00ce\u00cc\3\2\2\2\u00cf\u00c7\3\2\2\2\u00cf"+
    "\u00d0\3\2\2\2\u00d0\u00d1\3\2\2\2\u00d1\u00d2\7/\2\2\u00d2!\3\2\2\2\u00d3"+
    "\u00dc\7\21\2\2\u00d4\u00dc\5,\27\2\u00d5\u00dc\5&\24\2\u00d6\u00d8\7"+
    "\60\2\2\u00d7\u00d6\3\2\2\2\u00d8\u00d9\3\2\2\2\u00d9\u00d7\3\2\2\2\u00d9"+
    "\u00da\3\2\2\2\u00da\u00dc\3\2\2\2\u00db\u00d3\3\2\2\2\u00db\u00d4\3\2"+
    "\2\2\u00db\u00d5\3\2\2\2\u00db\u00d7\3\2\2\2\u00dc#\3\2\2\2\u00dd\u00de"+
    "\t\4\2\2\u00de%\3\2\2\2\u00df\u00e0\t\5\2\2\u00e0\'\3\2\2\2\u00e1\u00e2"+
    "\5*\26\2\u00e2)\3\2\2\2\u00e3\u00e6\7\63\2\2\u00e4\u00e6\7\64\2\2\u00e5"+
    "\u00e3\3\2\2\2\u00e5\u00e4\3\2\2\2\u00e6+\3\2\2\2\u00e7\u00ea\7\62\2\2"+
    "\u00e8\u00ea\7\61\2\2\u00e9\u00e7\3\2\2\2\u00e9\u00e8\3\2\2\2\u00ea-\3"+
    "\2\2\2\u00eb\u00ec\7\60\2\2\u00ec/\3\2\2\2\379=@EIMRV^fv~\u0080\u0085"+
    "\u0088\u0090\u0099\u009f\u00a6\u00ac\u00b8\u00ba\u00c3\u00cc\u00cf\u00d9"+
    "\u00db\u00e5\u00e9";
  public static final ATN _ATN =
    new ATNDeserializer().deserialize(_serializedATN.toCharArray());
  static {
    _decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
    for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
      _decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
    }
  }
}
