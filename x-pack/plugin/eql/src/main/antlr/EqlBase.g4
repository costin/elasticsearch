/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

grammar EqlBase;

tokens {
    DELIMITER
}

singleStatement
    : statement EOF
    ;

singleExpression
    : expression EOF
    ;

statement
    : sequence
    | join
    | query
    ;


sequence
    : SEQUENCE (by=joinKeys)? (span)?
      match+
      (UNTIL match)?
    ;

join
    : JOIN (by=joinKeys)?
      match+
      (UNTIL match)?
    ;

joinKeys
    : BY identifier (',' identifier)*
    ;

span
    : WITH MAXSPAN EQ number (unit=string)?
    ;

match
    : LB query RB
    ;

query
    : event=identifier WHERE expression
    ;

expression
    : booleanExpression
    ;

booleanExpression
    : NOT booleanExpression                                               #logicalNot
    | predicated                                                          #booleanDefault
    | left=booleanExpression operator=AND right=booleanExpression         #logicalBinary
    | left=booleanExpression operator=OR right=booleanExpression          #logicalBinary
    ;

// workaround for:
//  https://github.com/antlr/antlr4/issues/780
//  https://github.com/antlr/antlr4/issues/781
predicated
    : valueExpression predicate?
    ;

// dedicated calls for each branch are not used to reuse the NOT handling across them
// instead the property kind is used for differentiation
predicate
    : NOT? kind=BETWEEN lower=valueExpression AND upper=valueExpression
    | NOT? kind=IN LP valueExpression (',' valueExpression)* RP
    | NOT? kind=IN LP query RP
    ;

valueExpression
    : primaryExpression                                                                 #valueExpressionDefault
    | operator=(MINUS | PLUS) valueExpression                                           #arithmeticUnary
    | left=valueExpression operator=(ASTERISK | SLASH | PERCENT) right=valueExpression  #arithmeticBinary
    | left=valueExpression operator=(PLUS | MINUS) right=valueExpression                #arithmeticBinary
    | left=valueExpression comparisonOperator right=valueExpression                     #comparison
    ;

primaryExpression
    : constant                                                                          #constantDefault
    | functionExpression                                                                #function
    | '(' expression ')'                                                                #parenthesizedExpression
    ;

functionExpression
    : identifier LP (expression (',' expression)*)? RP
    ;

constant
    : NULL                                                                              #nullLiteral
    | number                                                                            #numericLiteral
    | booleanValue                                                                      #booleanLiteral
    | STRING+                                                                           #stringLiteral
    ;

comparisonOperator
    : EQ | NEQ | LT | LTE | GT | GTE
    ;

booleanValue
    : TRUE | FALSE
    ;

identifier
    : unquoteIdentifier
    ;

unquoteIdentifier
    : IDENTIFIER             #unquotedIdentifier
    | DIGIT_IDENTIFIER       #digitIdentifier
    ;

number
    : DECIMAL_VALUE  #decimalLiteral
    | INTEGER_VALUE  #integerLiteral
    ;

string
    : STRING
    ;

AND: 'AND';
ANY: 'ANY';
ASC: 'ASC';
BETWEEN: 'BETWEEN';
BY: 'BY';
CHILD: 'CHILD';
DESCENDANT: 'DESCENDANT';
EVENT: 'EVENT';
FALSE: 'FALSE';
IN: 'IN';
JOIN: 'JOIN';
MAXSPAN: 'MAXSPAN';
NOT: 'NOT';
NULL: 'NULL';
OF: 'OF';
OR: 'OR';
SEQUENCE: 'SEQUENCE';
TRUE: 'TRUE';
UNTIL: 'UNTIL';
WHERE: 'WHERE';
WITH: 'WITH';


// Pipes
PIPE: '|';
COUNT: 'COUNT';
FILTER: 'FILTER';
HEAD: 'HEAD';
SORT: 'SORT';
TAIL: 'TAIL';
UNIQUE_COUNT: 'UNIQUE_COUNT';

// Operators
EQ  : '=' | '==';
NEQ : '<>' | '!=';
LT  : '<';
LTE : '<=';
GT  : '>';
GTE : '>=';

PLUS: '+';
MINUS: '-';
ASTERISK: '*';
SLASH: '/';
PERCENT: '%';
DOT: '.';
LB: '[';
RB: ']';
LP: '(';
RP: ')';

STRING
    : '\'' ( ~'\'')* '\''
    | '"' ( ~'"' )* '"'
    ;

INTEGER_VALUE
    : DIGIT+
    ;

DECIMAL_VALUE
    : DIGIT+ DOT DIGIT*
    | DOT DIGIT+
    | DIGIT+ (DOT DIGIT*)? EXPONENT
    | DOT DIGIT+ EXPONENT
    ;

IDENTIFIER
    : (LETTER | '_') (LETTER | DIGIT | '_' | '@' )*
    ;

DIGIT_IDENTIFIER
    : DIGIT (LETTER | DIGIT | '_' | '@')+
    ;

fragment EXPONENT
    : 'E' [+-]? DIGIT+
    ;

fragment DIGIT
    : [0-9]
    ;

fragment LETTER
    : [A-Z]
    ;

SIMPLE_COMMENT
    : '//' ~[\r\n]* '\r'? '\n'? -> channel(HIDDEN)
    ;

BRACKETED_COMMENT
    : '/*' (BRACKETED_COMMENT|.)*? '*/' -> channel(HIDDEN)
    ;

WS
    : [ \r\n\t]+ -> channel(HIDDEN)
    ;

// Catch-all for anything we can't recognize.
// We use this to be able to ignore and recover all the text
// when splitting statements with DelimiterLexer
UNRECOGNIZED
    : .
    ;