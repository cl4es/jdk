#include "opto/idiomMatch.hpp"
#include "precompiled.hpp"
#include "opto/node.hpp"
#include "opto/mulnode.hpp"
#include "opto/addnode.hpp"
#include "opto/mulnode.hpp"

bool is_array_ptr(Node *n) {
  return n->is_Type() && n->as_Type()->type()->isa_aryptr() != NULL;
}

bool is_primitive_load(Node *n) {
  return n->is_Load() && is_java_primitive(n->bottom_type()->basic_type());
}

bool is_primitive_store(Node *n) {
  // FIXME: Verify that the store is primitive.
  return n->is_Store();
}

bool is_mul(Node *n) { return n->is_Mul(); }
bool is_add(Node *n) { return n->is_Add(); }
bool is_sub(Node *n) { return n->is_Sub(); }
bool is_lshift(Node *n) { return n->Opcode() == Op_LShiftI || n->Opcode() == Op_LShiftL; }

// Is integer valued binop?
bool is_binop_i(Node *n) {
  int opc = n->Opcode();
  return
    opc == Op_AddI ||
    opc == Op_SubI ||
    opc == Op_MulI ||
    opc == Op_DivI ||
    opc == Op_LShiftI;
}

// Is long valued binop?
bool is_binop_l(Node *n) {
  int opc = n->Opcode();
  return
    opc == Op_AddL ||
    opc == Op_SubL ||
    opc == Op_MulL ||
    opc == Op_DivL ||
    opc == Op_LShiftL;
}

// Is float valued binop?
bool is_binop_f(Node *n) {
  int opc = n->Opcode();
  return
    opc == Op_AddF ||
    opc == Op_SubF ||
    opc == Op_MulF ||
    opc == Op_DivF;
}

// Is double valued binop?
bool is_binop_d(Node *n) {
  int opc = n->Opcode();
  return
    opc == Op_AddD ||
    opc == Op_SubD ||
    opc == Op_MulD ||
    opc == Op_DivD;
}

bool is_binop(Node *n) {
  return
    is_binop_i(n) || is_binop_l(n) ||
    is_binop_f(n) || is_binop_d(n);
}


/****************************************************************
 * Matching.
 ****************************************************************/

ArrayAccessPattern *match_array_access(Node *start, Node *idx,
                                       NodePred start_predicate,
                                       bool allow_offset) {
  ArrayAccessPattern *result = new ArrayAccessPattern();

  ResourceMark rm;

  enum {
    LOAD_NODE,
    LOAD_CTRL,
    ARRAY,
    MEMORY,
    IDX_SHIFT_DISTANCE,
    IDX_OFFSET,
    ARRAY_BASE_OFFSET,
    CAST_II,

    N_REFS
  };

  MatchRefs refs(N_REFS);


  Pattern *exact_idx = new ExactNodePattern(idx);

  // FIXME: unnessecary initialization if allow_offset is false.
  Pattern *idx_offset = new OpcodePattern<3>
    (Op_AddI,
     ANY,
     exact_idx,
     new CapturePattern(IDX_OFFSET));

  Pattern *idx_pattern = allow_offset
    ? new OrPattern(idx_offset, exact_idx)
    : exact_idx;

  Pattern *pre_shift = new OpcodePattern<2> // LShiftL: Left-hand side
    (Op_ConvI2L,
     ANY,                                   // ConvI2L: Control
     new OpcodePattern<2>                   // ConvI2L: Data
     (Op_CastII,
      ANY,                                  // CastII:  Control
      idx_pattern,                          // CastII:  Index
      CAST_II));

  Pattern *shift = new OpcodePattern<3>     // AddP: Offset
    (Op_LShiftL,
     ANY,                                   // LShiftL: Control
     pre_shift,
     new OpcodePattern<0>(Op_ConI, IDX_SHIFT_DISTANCE));

  Pattern *p = new Pred2Pattern<3>
    (start_predicate,
     new CapturePattern(LOAD_CTRL),
     new CapturePattern(MEMORY),
     new OpcodePattern<4>
     (Op_AddP,
      ANY,
      ANY,
      new OpcodePattern<4>
      (Op_AddP,
       ANY,                            // AddP: Control
       ANY,                            // AddP: Base
       new PredPattern(is_array_ptr, ARRAY), // AddP: Address
       new OrPattern(shift, pre_shift)),
      new OpcodePattern<0>(Op_ConL, ARRAY_BASE_OFFSET)),
      LOAD_NODE);


  // NOTE: If we start at a ConvI2L, skip that node and force _bt to
  // T_LONG.
  bool is_long = false;
  if (start->Opcode() == Op_ConvI2L) {
    is_long = true;
    start = start->in(1);
  }

  if (p->match(start, refs)) {
    result->_load_ctrl = refs[LOAD_CTRL];
    result->_load = refs[LOAD_NODE];
    result->_bt = is_long ? T_LONG : result->_load->bottom_type()->basic_type();
    result->_index = idx;
    result->_offset = refs[IDX_OFFSET];
    result->_result = start;
    result->_array_ptr = refs[ARRAY];
    result->_memory = refs[MEMORY];
    result->_elem_byte_size =
      1 << (refs[IDX_SHIFT_DISTANCE] != NULL
            ? refs[IDX_SHIFT_DISTANCE]->get_int()
            : 0);
    result->_base_offset = refs[ARRAY_BASE_OFFSET]->get_long();

    return result;
  } else {
    TRACE(Match, {
        tty->print_cr("  origin array_read");
      });
    return NULL;
  }
}

ArrayLoadPattern *match_array_read(Node *start, Node *idx,
                                   bool allow_offset) {
  ArrayAccessPattern *p = match_array_access(start, idx, is_primitive_load, allow_offset);
  if (p == NULL) return NULL;
  return new ArrayLoadPattern(p);
}

ArrayStorePattern *match_array_store(Node *start, Node *idx,
                                     bool allow_offset) {
  ArrayAccessPattern *p = match_array_access(start, idx, is_primitive_store, allow_offset);
  if (p == NULL) return NULL;
  PatternInstance *stored_value = match(start->in(MemNode::ValueIn), idx);
  if (stored_value == NULL) return NULL;

  ArrayStorePattern *sp = new ArrayStorePattern(p, stored_value);
  return sp;
}

PatternInstance *match_binop(Node *start, Node *iv) {
  // Only accept binary operations without control dependence.
  if (!(is_binop(start) && start->in(0) == NULL)) return NULL;

  Node *lhs = start->in(1);
  Node *rhs = start->in(2);
  assert(lhs != NULL && rhs != NULL, "sanity");

  PatternInstance *lhs_p = match(lhs, iv);
  if (lhs_p == NULL) return NULL;
  PatternInstance *rhs_p = match(rhs, iv);
  if (rhs_p == NULL) return NULL;

  BinOpPattern *pi = start->Opcode() != Op_LShiftI
    ? new BinOpPattern(start->Opcode(), lhs_p, rhs_p, start)
    : new LShiftPattern(start->Opcode(), lhs_p, rhs_p, start);

  return pi;
}

PatternInstance *match_scalar(Node *start) {
  // NOTE: Assumes the scalar to be loop invariant. Presence of loop
  // variant scalars should exit idiom vectorization early. To account
  // for this, we currently only accept scalar constants.
  if (start->Opcode() == Op_ConI || start->Opcode() == Op_ConF ||
      start->Opcode() == Op_ConD) {
    return new ScalarPattern(start);
  } else if (start->Opcode() == Op_Phi) {
    return new ScalarPattern(start);
  } else {
    return NULL;
  }
}

PatternInstance *match_int_demotion(const Node *start, Node *iv) {
  if (start->Opcode() == Op_RShiftI &&
      start->in(1)->Opcode() == Op_LShiftI &&
      start->in(2)->Opcode() == Op_ConI &&
      start->in(1)->in(2) == start->in(2)) {
    BasicType bt;
    Node *con = start->in(2);
    switch (con->get_int()) {
    case 16: bt = T_SHORT; break;
    case 24: bt = T_BYTE; break;
    default: return NULL;
    }

    const Type *resulting_type = Type::get_const_basic_type(bt);
    PatternInstance *demoted = match(start->in(1)->in(1), iv);
    if (demoted == NULL) return NULL;

    return new IntDemotionPattern(resulting_type, demoted);
  } else {
    return NULL;
  }
}


PatternInstance *match(Node *start, Node *iv) {
  PatternInstance *pi;
  if (pi = match_array_read(start, iv, true))
    return pi;
  if (pi = match_array_store(start, iv))
    return pi;
  if (pi = match_int_demotion(start, iv))
    return pi;
  if (pi = match_binop(start, iv))
    return pi;
  if (pi = match_scalar(start))
    return pi;

  TRACE(Match, {
      tty->print_cr("Unable to find pattern instance.");
      tty->print("  "); start->dump(" start node");
    });

  return NULL;
}
