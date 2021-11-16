#include "opto/idiomPattern.hpp"

#include "precompiled.hpp"
#include "opto/idiomVectorize.hpp"
#include "opto/node.hpp"
#include "opto/addnode.hpp"
#include "opto/memnode.hpp"
#include "opto/mulnode.hpp"
#include "opto/connode.hpp"
#include "opto/type.hpp"
#include "opto/vectornode.hpp"
#include "opto/callnode.hpp"
#include "opto/output.hpp"
#include "opto/castnode.hpp"
#include "opto/convertnode.hpp"


/****************************************************************
 * Reduce.
 ****************************************************************/
PatternInstance *ArrayStorePattern::reduce(Node *reduction_phi, Node *iv) {
  _stored_value = _stored_value->reduce(reduction_phi, iv);

  if (PatternInstance *p = PrefixSumPattern::make(this))
    return p;

  return this;
}

PatternInstance *ScalarPattern::reduce(Node *reduction_phi, Node *iv) {
  if (PatternInstance *p = ReductionVariablePattern::make(this, reduction_phi))
    return p;
  return this;
}

PatternInstance *BinOpPattern::reduce(Node *reduction_phi, Node *iv) {
  _lhs = _lhs->reduce(reduction_phi, iv);
  _rhs = _rhs->reduce(reduction_phi, iv);

  PatternInstance *p;
  if (p = PrefixBinOpPattern::make(this))
    return p;
  if (p = ScaledReductionVariablePattern::make(this))
    return p;
  if (p = ReductionPattern::make(this))
    return p;

  return this;
}

PatternInstance *IntDemotionPattern::reduce(Node *reduction_phi, Node *iv) {
  _demoted = _demoted->reduce(reduction_phi, iv);
  if (_demoted->op() == Reduction &&
      // NOTE: *ReductionVBNode not implemented.
      _resulting_type->array_element_basic_type() != T_BYTE) {
    ReductionPattern *r = static_cast<ReductionPattern*>(_demoted);
    r->_velt = _resulting_type->array_element_basic_type();
    return r;
  }

  return this;
}

/****************************************************************
 * Reduction factories.
 ****************************************************************/
PrefixBinOpPattern *PrefixBinOpPattern::make(BinOpPattern *binop) {
  if (binop->_lhs->op() == ArrayLoad && binop->_rhs->op() == ArrayLoad &&
      binop->_opcode == Op_AddI) {
    ArrayLoadPattern *lhs = static_cast<ArrayLoadPattern *>(binop->_lhs);
    ArrayLoadPattern *rhs = static_cast<ArrayLoadPattern *>(binop->_rhs);

    ArrayLoadPattern *prefix;
    ArrayLoadPattern *c;

    if (lhs->_access->_offset == NULL && rhs->_access->_offset == NULL) {
      return NULL;
    } else if (lhs->_access->_offset == NULL && rhs->_access->_offset != NULL) {
      prefix = rhs;
      c = lhs;
    } else if (rhs->_access->_offset == NULL && lhs->_access->_offset != NULL) {
      prefix = lhs;
      c = rhs;
    } else {
      return NULL;
    }

    bool prefix_has_decremented_offset =
        prefix->_access->_offset->is_Con() &&
        prefix->_access->_offset->get_int() == -1;
    if (!prefix_has_decremented_offset)
      return NULL;

    return new PrefixBinOpPattern(prefix, c);
  }

  return NULL;
}

ReductionVariablePattern *ReductionVariablePattern::make(ScalarPattern *p,
                                                         Node *reduction_phi) {
  if (p->_scalar == reduction_phi)
    return new ReductionVariablePattern(reduction_phi);
  else return NULL;
}

PatternInstance *reduction_variable(PatternInstance *p, int binopcode) {
  if (p->op() == PatternInstance::ScaledReductionVariable &&
      is_associative(binopcode))
    return p;
  else if (p->op() == PatternInstance::ReductionVariable &&
           is_semiassociative(binopcode))
    return p;
  else
    return NULL;
}

ReductionPattern *ReductionPattern::make(BinOpPattern *p) {
  PatternInstance *rv = reduction_variable(p->_lhs, p->_opcode);
  if (rv == NULL)  rv = reduction_variable(p->_rhs, p->_opcode);
  if (rv == NULL)  return NULL;

  PatternInstance *c_term = p->_lhs == rv ? p->_rhs : p->_lhs;

  // Longs not supported on AVX2.
  if (rv->velt() == T_LONG || c_term->velt() == T_LONG) return NULL;

  if (rv->op() == ScaledReductionVariable) {
    JavaValue n = static_cast<ScaledReductionVariablePattern*>(p->_lhs)->_scale;
    return new ReductionPattern(p->_opcode, p->_lhs, p->_rhs, n);
  } else if (rv->op() == ReductionVariable) {
    return new ReductionPattern(p->_opcode, p->_lhs, p->_rhs);
  } else {
    return NULL;
  }
}

ReductionPattern *ReductionPattern::make(IntDemotionPattern *p) {
  if (p->_demoted->op() != p->Reduction) return NULL;
  return NULL;
}

ScaledReductionVariablePattern *
ScaledReductionVariablePattern::make(BinOpPattern *p) {
  if (p->_lhs->op() == ReductionVariable &&
      p->_rhs->op() == Scalar) {

    ScalarPattern *con = static_cast<ScalarPattern*>(p->_rhs);
    switch (p->_opcode) {
    case Op_LShiftI:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(1 << con->_scalar->get_int()));
    case Op_LShiftL:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(1 << con->_scalar->get_int()));
    case Op_MulI:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(con->_scalar->get_int()));
    case Op_MulL:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(con->_scalar->get_long()));
    case Op_MulF:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(con->_scalar->getf()));
    case Op_MulD:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(con->_scalar->getd()));
    default:
      return NULL;
    }
  } else if (p->_lhs->op() == ScaledReductionVariable &&
             p->_rhs->op() == ReductionVariable) {

    JavaValue scale = static_cast<ScaledReductionVariablePattern*>(p->_lhs)->_scale;
    switch (p->_opcode) {
    case Op_AddI:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(scale.get_jint() + 1));
    case Op_SubI:
      return new ScaledReductionVariablePattern(p->_lhs, JavaValue(scale.get_jint() - 1));
    default:
      return NULL;
    }
  }

  return NULL;
}

/****************************************************************
 * Generate.
 ****************************************************************/
Node *ArrayLoadPattern::generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                                 Node *reduction_phi, Node *iv,
                                 Node *loop_entry_ctrl,
                                 Node_List& pre_old_new,
                                 IdealLoopTree* lpt) {
  // TODO: Can be a loop invariant:
  assert(_access->_offset == NULL || lpt->is_invariant(_access->_offset),
         "only no or loop invariant offsets accepted");
  BasicType recurr_bt = recurr_t->array_element_basic_type();

  LoadNode *load = _access->_load->as_Load();
  BasicType load_type = _access->_bt;
  BasicType elem_type = load->memory_type();

  TRACE(Rewrite, {
    tty->print_cr("load: %s, elem: %s, recur: %s", type2name(load_type),
                  type2name(elem_type), type2name(recurr_bt));
  });

  // No implicit cast.
  if (elem_type == recurr_bt) {
    Node *arr = LoadVectorNode::make(
        _access->_load->Opcode(), _access->_load->in(LoadNode::Control), _access->_memory,
        _access->_load->in(LoadNode::Address), _access->_load->adr_type(), vlen, recurr_bt);
    phase->igvn().register_new_node_with_optimizer(arr, NULL);
    return arr;
  } else {
    Node *arr = LoadVectorNode::make_promotion(
        _access->_load->Opcode(), _access->_load->in(LoadNode::Control), _access->_memory,
        _access->_load->in(LoadNode::Address), _access->_load->adr_type(), vlen, recurr_bt);
    phase->igvn().register_new_node_with_optimizer(arr, NULL);
    return arr;
  }

  ShouldNotReachHere();
  return NULL;
}

Node *ScalarPattern::generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                              Node *reduction_phi, Node *iv,
                              Node *loop_entry_ctrl,
                              Node_List& pre_old_new,
                              IdealLoopTree* lpt) {
  assert(lpt->is_invariant(_scalar),
         "only loop invariant scalars allowed");
  return make_vector(phase, _scalar, recurr_t, vlen);
}

Node *BinOpPattern::generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                             Node *reduction_phi, Node *iv,
                             Node *loop_entry_ctrl,
                             Node_List& pre_old_new,
                             IdealLoopTree* lpt) {
  Node *lhs = _lhs->generate(phase, recurr_t, vlen, reduction_phi, iv, loop_entry_ctrl,
                             pre_old_new, lpt);
  Node *rhs = _rhs->generate(phase, recurr_t, vlen, reduction_phi, iv, loop_entry_ctrl,
                             pre_old_new, lpt);

  Node *result = VectorNode::make(_opcode, lhs, rhs, vlen,
                                  recurr_t->array_element_basic_type());
  phase->igvn().register_new_node_with_optimizer(result);
  return result;
}


Node *LShiftPattern::generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                              Node *reduction_phi, Node *iv,
                              Node *loop_entry_ctrl,
                              Node_List& pre_old_new,
                              IdealLoopTree* lpt) {
  assert(_opcode == Op_LShiftI, "sanity");
  assert(_rhs->result()->is_Con(), "not implemented");

  BasicType recurr_bt = recurr_t->array_element_basic_type();
  Node *lhs = _lhs->generate(phase, recurr_t, vlen, reduction_phi, iv,
                             loop_entry_ctrl, pre_old_new, lpt);

  Node *multiplier = phase->igvn().intcon(1 << _rhs->result()->get_int());
  // TODO: `make_vector` needs control dependency on loop entry
  // control, without this dependency the vector initialization may
  // be scheduled before the deciding on vector/scalar loop.
  Node *result = VectorNode::make(
      Op_MulI, lhs, make_vector(phase, multiplier, recurr_t, vlen), vlen,
      recurr_bt);
  // new MulVINode(lhs, make_vector(phase, multiplier, vlen),
  // TypeVect::make(recurr_bt, vlen));
  phase->igvn().register_new_node_with_optimizer(result);
  return result;
}

Node *PrefixSumPattern::generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                                 Node *reduction_phi, Node *iv,
                                 Node *loop_entry_ctrl,
                                 Node_List& pre_old_new,
                                 IdealLoopTree* lpt) {
  PhaseIterGVN& igvn = phase->igvn();

  Node *store = this->_access->_load;
  const BasicType recurr_bt = recurr_t->array_element_basic_type();
  const int ELEM_SZ = type2aelembytes(recurr_bt);

  // Plug in the exit prefix from the pre-loop.
  Node *pre_prefix = pre_old_new[store->_idx]->in(MemNode::ValueIn);
  Node *initial_prefix = VectorNode::scalar2vector(pre_prefix, vlen, recurr_t);
  initial_prefix->init_req(0, loop_entry_ctrl);
  phase->register_node(initial_prefix, lpt, loop_entry_ctrl, 0);

  PhiNode *prefix_phi = PhiNode::make(iv->in(PhiNode::Region), initial_prefix);
  phase->register_node(prefix_phi, lpt, iv->in(PhiNode::Region), 0);
  // igvn.register_new_node_with_optimizer(prefix_phi);

  Node *c_load = stored_value()->_c->generate(phase, recurr_t, vlen,
                                              reduction_phi, iv,
                                              loop_entry_ctrl,
                                              pre_old_new,
                                              lpt);
  // Hillis and Steele parallel prefix sum performed in vector sized
  // chunks.
  Node *last_add = c_load;
  for (uint i = 1; i < vlen; i *= 2) {
    Node *shift = new ElemLShiftVNode(last_add, igvn.intcon(ELEM_SZ*i),
                                      TypeVect::make(recurr_bt, vlen));
    Node *add = VectorNode::make(Op_AddI, last_add, shift, vlen, recurr_bt);
    igvn.register_new_node_with_optimizer(shift);
    igvn.register_new_node_with_optimizer(add);
    last_add = add;
  }

  Node *prev_prefix_add = VectorNode::make(Op_AddI, prefix_phi, last_add, vlen, recurr_bt);
  igvn.register_new_node_with_optimizer(prev_prefix_add);


  // NOTE: ExtractI nodes are not currently implemented except for
  // when used in combination with a ReplicateI node.
  Node *extract_last = new ExtractINode(prev_prefix_add, igvn.intcon(vlen - 1));
  igvn.register_new_node_with_optimizer(extract_last);
  Node *repl_last = new ReplicateINode(extract_last, TypeVect::make(recurr_bt, vlen));
  igvn.register_new_node_with_optimizer(repl_last);
  prefix_phi->set_req(2, repl_last);


  assert(store->is_Store(), "sanity");
  StoreVectorNode *storev = StoreVectorNode::make(store->Opcode(),
                                                  store->in(MemNode::Control),
                                                  store->in(MemNode::Memory),
                                                  store->in(MemNode::Address),
                                                  store->adr_type(), prev_prefix_add, vlen);
  igvn.register_new_node_with_optimizer(storev);
  return storev;
}

Node *ReductionPattern::generate(PhaseIdealLoop *phase,
                                 const Type *recurr_t, uint vlen,
                                 Node *reduction_phi, Node *iv,
                                 Node *loop_entry_ctrl,
                                 Node_List &pre_old_new,
                                 IdealLoopTree* lpt) {
  const BasicType recurr_bt = recurr_t->array_element_basic_type();

  Node *c = _c->generate(phase, recurr_t, vlen, reduction_phi, iv,
                         loop_entry_ctrl, pre_old_new, lpt);

  Node *identity = phase->igvn().transform(identity_con(_opcode));
  Node *identities = make_vector(phase, identity, recurr_t, vlen, loop_entry_ctrl);
  phase->set_ctrl(identities, loop_entry_ctrl);

  Node *initial_acc = new PromoteNode(identities, reduction_phi->in(1),
                                      TypeVect::make(recurr_bt, vlen));
  initial_acc->init_req(0, loop_entry_ctrl);
  phase->register_node(initial_acc, lpt, loop_entry_ctrl, 0);
  // phase->igvn().register_new_node_with_optimizer(initial_acc);

  Node *m = make_exp_vector(phase, _n, vlen, recurr_t, loop_entry_ctrl);
  Node *phi = PhiNode::make(iv->in(PhiNode::Region), initial_acc);

  Node *mul0;

  int op_mul = mul_opcode(recurr_bt);
  int op_add = add_opcode(recurr_bt);

  if (_scaled) {
    Node *mulv = make_vector(phase, make_pow(_n, vlen, recurr_bt), recurr_t, vlen, loop_entry_ctrl);
    phase->set_ctrl(mulv, loop_entry_ctrl);
    mul0 = phase->igvn().transform(VectorNode::make(op_mul, mulv, phi, vlen, recurr_bt));
  } else {
    mul0 = phi;
  }

  Node *mul1;
  if (_scaled) {
    mul1 = VectorNode::make(op_mul, c, m, vlen, recurr_bt);
    phase->igvn().register_new_node_with_optimizer(mul1);
  } else {
    mul1 = c;
  }

  Node *add = VectorNode::make(_opcode, mul0, mul1, vlen, recurr_bt);
  phi->set_req(2, add);

  phase->register_node(phi, lpt, iv->in(PhiNode::Region), 0);

  phase->igvn().register_new_node_with_optimizer(add);

  int reduce_opcode = reduction_opcode(_opcode);
  Node *reduce = ReductionNode::make(reduce_opcode, NULL, identity, add, recurr_bt);
  phase->igvn().register_new_node_with_optimizer(reduce);

  return reduce;
}

/****************************************************************
 * Dump.
 ****************************************************************/
void ArrayLoadPattern::dump(int indent) {
  print_indent(indent);
  tty->print("ARRAYLOAD[N%d", _access->_index->_idx);
  if (_access->_offset != NULL) {
    if (_access->_offset->Opcode() == Op_ConI)
      tty->print_cr(" + %d]", _access->_offset->get_int());
    else
      tty->print_cr(" + N%d]", _access->_offset->_idx);
  } else {
    tty->print_cr("]");
  }
}

void ArrayStorePattern::dump(int indent) {
  print_indent(indent);
  tty->print("ARRAYSTORE[N%d", _access->_index->_idx);
  if (_access->_offset != NULL) {
    if (_access->_offset->Opcode() == Op_ConI)
      tty->print_cr(" + %d]", _access->_offset->get_int());
    else
      tty->print_cr(" + N%d]", _access->_offset->_idx);
  } else {
    tty->print_cr("]");
  }

  _stored_value->dump(indent + 1);
}

/****************************************************************
 * Misc.
 ****************************************************************/
BasicType BinOpPattern::velt() {
  return this->_origin->bottom_type()->array_element_basic_type();
}
