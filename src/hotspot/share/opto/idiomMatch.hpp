#ifndef SHARE_OPTO_IDIOMMATCH_HPP
#define SHARE_OPTO_IDIOMMATCH_HPP

//#include "precompiled.hpp"
#include "utilities/globalDefinitions.hpp"
#include "opto/node.hpp"
#include "opto/idiomVectorize.hpp"
#include "opto/idiomPattern.hpp"

/****************************************************************
 * Predicates.
 ****************************************************************/
bool is_array_ptr(Node *n);
// Load of primitive value?
bool is_primitive_load(Node *n);
// Store of primitive value?
bool is_primitive_store(Node *n);
bool is_mul(Node *n);
bool is_add(Node *n);
bool is_sub(Node *n);
bool is_lshift(Node *n);
// Is integer valued binop?
bool is_binop_i(Node *n);
// Is long valued binop?
bool is_binop_l(Node *n);
// Is float valued binop?
bool is_binop_f(Node *n);
// Is double valued binop?
bool is_binop_d(Node *n);
bool is_binop(Node *n);


#define ANY (Pattern*)NULL
const bool TRACE_MATCHING = true;

typedef bool (*NodePred)(Node *);

class MatchRefs : public ResourceObj {
  static const int MAX_REFS = 32;
  int _n;
  Node *_refs[MAX_REFS];

public:
  MatchRefs(int n) : _n(n) {
    assert(n <= MAX_REFS, "maximum number of references reached");
    for (int i = 0; i < n; ++i) _refs[i] = NULL;
  }

  inline Node *&operator[](int i) {
    guarantee(i < _n, "out of bounds");
    return _refs[i];
  }
};


class Pattern : public ResourceObj {
protected:
  static const int NO_REF = -1;
  inline void set_ref(Node *n, MatchRefs &refs) {
    if (_ref != NO_REF) refs[_ref] = n;
  }
public:
  int _ref;

  Pattern(int ref) : _ref(ref) {}
  virtual ~Pattern() {}
  virtual bool match(Node *n, MatchRefs& refs) = 0;
};

// TODO: Make Opcode a field instead of a template parameter.
template<uint NSubpatterns>
class OpcodePattern : public Pattern {
public:
  int _opcode;
  Pattern* _subpatterns[NSubpatterns];

  OpcodePattern(int opcode, int ref=NO_REF)
    : Pattern(ref), _opcode(opcode) {
    assert(NSubpatterns == 0, "expected");
  }

  OpcodePattern(int opcode, Pattern *p0, int ref=NO_REF)
    : Pattern(ref), _opcode(opcode) {
    assert(NSubpatterns == 1, "expected");
    _subpatterns[0] = p0;
  }

  OpcodePattern(int opcode, Pattern *p0, Pattern *p1, int ref=NO_REF)
    : Pattern(ref), _opcode(opcode) {
    assert(NSubpatterns == 2, "expected");
    _subpatterns[0] = p0;
    _subpatterns[1] = p1;
  }

  OpcodePattern(int opcode, Pattern *p0, Pattern *p1, Pattern *p2, int ref=NO_REF)
    : Pattern(ref), _opcode(opcode) {
    assert(NSubpatterns == 3, "expected");
    _subpatterns[0] = p0; _subpatterns[1] = p1;
    _subpatterns[2] = p2;
  }

  OpcodePattern(int opcode, Pattern *p0, Pattern *p1, Pattern *p2, Pattern *p3, int ref=NO_REF)
    : Pattern(ref), _opcode(opcode) {
    assert(NSubpatterns == 4, "expected");
    _subpatterns[0] = p0; _subpatterns[1] = p1;
    _subpatterns[2] = p2; _subpatterns[3] = p3;
  }

  bool match(Node *n, MatchRefs &refs) {
    if (n->Opcode() != _opcode) {
      set_ref(NULL, refs);
      return false;
    }

    for (uint i = 0; i < (NSubpatterns < n->req() ? NSubpatterns : n->req()); i++) {
      Node *next = n->in(i);
      Pattern *sp = _subpatterns[i];
      if (sp != ANY) {
        if (next == NULL || !sp->match(next, refs)) {
          TRACE(Match, {
              tty->print("[OpcodePattern] Matching failed for in(%d)", i);
              n->dump();
              next != NULL ? next->dump("  found\n") : tty->print_cr("  NULL found");
            });
          set_ref(NULL, refs);
          return false;
        }
      }
    }

    set_ref(n, refs);
    return true;
  }
};


class OrPattern : public Pattern {
public:
  // Only match this exact node.
  Pattern *_p0;
  Pattern *_p1;

  OrPattern(Pattern* p0, Pattern *p1)
    : Pattern(NO_REF), _p0(p0), _p1(p1) {};

  bool match(Node *n, MatchRefs &refs) {
    return _p0->match(n, refs) || _p1->match(n, refs);
  }
};

class ExactNodePattern : public Pattern {
public:
  // Only match this exact node.
  Node *_n;

  ExactNodePattern(Node *n) : Pattern(NO_REF), _n(n) {};

  bool match(Node *n, MatchRefs &refs) {
    return n == _n;
  }
};

class PredPattern : public Pattern {
private:
  NodePred _pred;
public:
  PredPattern(NodePred pred, int ref=NO_REF) : Pattern(ref), _pred(pred) {}

  bool match(Node *n, MatchRefs &refs) {
    if (_pred(n)) {
      set_ref(n, refs);
      return true;
    } else {
      set_ref(NULL, refs);
      return false;
    }
  }
};

template<uint NSubpatterns>
class Pred2Pattern : public Pattern {
public:
  NodePred _pred;
  Pattern* _subpatterns[NSubpatterns];

  Pred2Pattern(NodePred pred, int ref=NO_REF)
    : Pattern(ref), _pred(pred) {
    assert(NSubpatterns == 0, "expected");
  }

  Pred2Pattern(NodePred pred, Pattern *p0, int ref=NO_REF)
    : Pattern(ref), _pred(pred) {
    assert(NSubpatterns == 1, "expected");
    _subpatterns[0] = p0;
  }

  Pred2Pattern(NodePred pred, Pattern *p0, Pattern *p1, int ref=NO_REF)
    : Pattern(ref), _pred(pred) {
    assert(NSubpatterns == 2, "expected");
    _subpatterns[0] = p0;
    _subpatterns[1] = p1;
  }

  Pred2Pattern(NodePred pred, Pattern *p0, Pattern *p1, Pattern *p2, int ref=NO_REF)
    : Pattern(ref), _pred(pred) {
    assert(NSubpatterns == 3, "expected");
    _subpatterns[0] = p0; _subpatterns[1] = p1;
    _subpatterns[2] = p2;
  }

  Pred2Pattern(NodePred pred, Pattern *p0, Pattern *p1, Pattern *p2, Pattern *p3, int ref=NO_REF)
    : Pattern(ref), _pred(pred) {
    assert(NSubpatterns == 4, "expected");
    _subpatterns[0] = p0; _subpatterns[1] = p1;
    _subpatterns[2] = p2; _subpatterns[3] = p3;
  }

  bool match(Node *n, MatchRefs &refs) {
    if (!_pred(n)) {
      set_ref(NULL, refs);
      return false;
    }

    for (uint i = 0; i < n->req() && i < NSubpatterns; i++) {
      Node *next = n->in(i);
      Pattern *sp = _subpatterns[i];
      if (sp != ANY) {
        if (next == NULL || !sp->match(next, refs)) {
          TRACE(Match, {
              tty->print("[Pred2Pattern] Matching failed for in(%d):", i);
              n->dump();
              next != NULL ? next->dump("  ") : tty->print("  NULL");
              tty->print_cr("");
            });
          set_ref(NULL, refs);
          return false;
        }
      }
    }

    set_ref(n, refs);
    return true;
  }
};


// Unconditionally matches a node, saving it as a ref.
class CapturePattern : public Pattern {
public:
  CapturePattern(int ref) : Pattern(ref) {}
  bool match(Node *n, MatchRefs &refs) {
    set_ref(n, refs);
    return true;
  }
};

/****************************************************************
 * Matching.
 ****************************************************************/
ArrayAccessPattern *match_array_access(Node *start, Node *idx,
                                     NodePred start_predicate,
                                     bool allow_offset=false);
ArrayLoadPattern *match_array_read(Node *start, Node *idx,
                                   bool allow_offset = false);
ArrayStorePattern *match_array_store(Node *start, Node *idx,
                                    bool allow_offset = false);
PatternInstance *match_binop(Node *start, Node *iv);
PatternInstance *match_scalar(Node *start);
PatternInstance *match_prefix_sum(Node *start, Node *iv);
PatternInstance *match(Node *start, Node *iv);

#endif
