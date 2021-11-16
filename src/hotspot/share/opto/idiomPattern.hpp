#ifndef SHARE_OPTO_IDIOMPATTERN_HPP
#define SHARE_OPTO_IDIOMPATTERN_HPP

#include "opto/idiomVectorize.hpp"
#include "memory/allocation.hpp"
#include "opto/node.hpp"
#include "opto/connode.hpp"
#include "opto/loopnode.hpp"

struct AlignInfo : ResourceObj {
  Node *_base_ptr;
  int _base_offset;
  int _preferred_align;
  int _bytes_per_iter;

  AlignInfo(Node *base_ptr, int base_offset, int preferred_align,
            int bytes_per_iter)
      : _base_ptr(base_ptr), _base_offset(base_offset),
        _preferred_align(preferred_align),
        _bytes_per_iter(bytes_per_iter) {}
};

struct PatternInstance : ResourceObj {
  enum {
    ArrayAccess,
    ArrayLoad,
    ArrayStore,
    BinOp,
    Scalar,
    IntDemotion,
    ReductionVariable,

    ScaledReductionVariable,
    PrefixBinOp,

    PrefixSum,
    Reduction,
  };

  // Vector element type.
  virtual BasicType velt() = 0;

  /**************************************************************
   * Pure virtuals.
   **************************************************************/
  virtual int op() = 0;
  virtual void dump(int indent=0) = 0;

  virtual BasicType bt() { ShouldNotCallThis(); return T_ILLEGAL; };

  // Generate Node.
  virtual bool is_rewritable_idiom() { return false; }
  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t,
                         uint vlen, Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt) {
    ShouldNotCallThis();
    return NULL;
  }
  virtual Node *result() { ShouldNotCallThis(); return NULL; };

  virtual bool has_alignable_load() { return false; }
  virtual int memory_stride() { return 0; }
  virtual BasicType elem_bt() { ShouldNotCallThis(); return T_ILLEGAL; }
  virtual Node *base_addr() { ShouldNotCallThis(); return NULL; };
  virtual AlignInfo *align_info(int vlen) { return NULL; }

  static void print_indent(int indent) {
    while (indent--) tty->print(" ");
  }

  // TODO: make pure virtual.
  virtual PatternInstance *reduce(Node *reduction_phi, Node *iv) { return this; };
};

/****************************************************************
 * Primitive patterns.
 ****************************************************************/
struct ArrayAccessPattern : PatternInstance {
  // Basic type of loaded value.
  BasicType _bt;

  // Load node.
  Node *_load;

  // Load control dep.
  Node *_load_ctrl;

  // Node indexing the array.
  Node *_index;

  // Index offset.
  Node *_offset;

  // Array being indexed.
  Node *_array_ptr;

  // Memory state.
  Node *_memory;

  // Node producing the result.
  Node *_result;

  // Size, in bytes, of each element.
  jint _elem_byte_size;

  // Base offset of the array.
  jlong _base_offset;

  ArrayAccessPattern(){}

  virtual BasicType velt() { return _load->as_Mem()->memory_type(); };
  virtual int op() { return ArrayAccess; }
  virtual Node *result() { return _load; }
  virtual bool has_alignable_load() { return true; }
  virtual int memory_stride() { return _elem_byte_size; }
  virtual BasicType elem_bt() { return _load->as_Mem()->memory_type(); }
  virtual AlignInfo *align_info(int vlen) {
    int base_offset = arrayOopDesc::base_offset_in_bytes(velt());
    int bytes_per_iter = type2aelembytes(velt());
    // NOTE: AMD Ryzen specific, 256 bit loads need only be aligned to
    // 16 bit congruent addresses.
    int preferred_align = MIN2(vlen * bytes_per_iter, 16);
    return new AlignInfo(_array_ptr, base_offset,
                         preferred_align,
                         bytes_per_iter);
  }
  virtual Node *base_addr() { return _array_ptr; }
  virtual void dump(int indent = 0) { ShouldNotCallThis(); }
};

// Array load pattern instance.
struct ArrayLoadPattern : PatternInstance {
  ArrayAccessPattern *_access;

  ArrayLoadPattern(ArrayAccessPattern *access)
    : _access(access) {}

  virtual BasicType velt() { return _access->elem_bt(); }
  virtual int op() { return ArrayLoad; }
  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                         Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt);
  virtual Node *result() { return _access->result(); }
  virtual bool has_alignable_load() { return true; }
  virtual int memory_stride() { return _access->memory_stride(); }
  virtual BasicType elem_bt() { return _access->elem_bt(); }
  virtual Node *base_addr() { return _access->base_addr(); }
  virtual void dump(int indent=0);
  virtual BasicType bt() { return elem_bt(); };
  virtual AlignInfo *align_info(int vlen) { return _access->align_info(vlen); }
};

struct ArrayStorePattern : PatternInstance {
  ArrayAccessPattern *_access;
  PatternInstance *_stored_value;

  ArrayStorePattern(ArrayAccessPattern *access, PatternInstance *stored_value)
    : _access(access), _stored_value(stored_value) {}

  virtual BasicType velt() { return _stored_value->velt(); };
  virtual int op() { return ArrayStore; }
  virtual Node *result() { return _access->result(); }
  virtual bool has_alignable_load() { return true; }
  virtual int memory_stride() { return _access->memory_stride(); }
  virtual BasicType elem_bt() { return _access->elem_bt(); }
  virtual Node *base_addr() { return _access->base_addr(); }
  virtual void dump(int indent=0);

  virtual AlignInfo *align_info(int vlen) { return _access->align_info(vlen); }

  virtual PatternInstance *reduce(Node *reduction_phi, Node *iv);
};

struct ScalarPattern : PatternInstance {
  Node *_scalar;

  ScalarPattern(Node *scalar)
    : _scalar(scalar) { }

  virtual BasicType velt(){
    // TODO: Make sure int constant types gets sufficiently narrowed.
    return _scalar->as_Type()->bottom_type()->array_element_basic_type();
  }

  virtual int op() { return Scalar; }
  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                         Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt);

  virtual Node *result() {
    return _scalar;
  }


  virtual bool has_alignable_load() { return false; }
  virtual Node *base_addr() { ShouldNotCallThis(); return NULL; }
  virtual void dump(int indent = 0) {
    print_indent(indent);
    tty->print_cr("SCALAR");
  }

  virtual PatternInstance *reduce(Node *reduction_phi, Node *iv);
};

struct ReductionVariablePattern : PatternInstance {
  Node *_v;

  ReductionVariablePattern(Node *v)
    : _v(v) {}

  virtual BasicType velt() {
    return _v->as_Type()->bottom_type()->array_element_basic_type();
  }

  virtual int op() { return ReductionVariable; }
  virtual void dump(int indent=0) {
    print_indent(indent);
    tty->print_cr("REDUCTIONVAR");
  }

  static ReductionVariablePattern *make(ScalarPattern *p, Node *reduction_phi);
};

struct BinOpPattern : PatternInstance {
  int _opcode;
  PatternInstance *_lhs, *_rhs;
  Node *_origin;

  BinOpPattern(int opcode,
               PatternInstance *lhs,
               PatternInstance *rhs,
               Node *origin)
    : _opcode(opcode), _lhs(lhs), _rhs(rhs), _origin(origin) {}

  virtual BasicType velt();

  virtual int op() { return BinOp; }

  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                         Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt);

  virtual Node *result() {
    ShouldNotCallThis();
    return NULL;
  }

  virtual bool has_alignable_load() {
    return
      _lhs->has_alignable_load() ||
      _rhs->has_alignable_load();
  }

  virtual BasicType elem_bt() {
    if (_lhs->has_alignable_load()) return _lhs->elem_bt();
    else return _rhs->elem_bt();
  }

  virtual Node *base_addr() {
    if (_lhs->has_alignable_load()) return _lhs->base_addr();
    else return _rhs->base_addr();
  }

  virtual AlignInfo *align_info(int vlen) {
    AlignInfo *lhs = _lhs->align_info(vlen);
    AlignInfo *rhs = _rhs->align_info(vlen);

    if (rhs == NULL && lhs == NULL)
      return NULL;

    if (lhs == NULL) {
      lhs = rhs;
    } else if (rhs == NULL) {
      rhs = lhs;
    }

    if (lhs->_base_ptr != rhs->_base_ptr ||
        // TODO: Investigate if the following conditions can still
        // result in alignment:
        lhs->_bytes_per_iter != rhs->_bytes_per_iter ||
        lhs->_base_offset != rhs->_base_offset)
      return NULL;

    return new AlignInfo(lhs->_base_ptr, lhs->_base_offset,
                         MAX(lhs->_preferred_align,
                             rhs->_preferred_align),
                         lhs->_bytes_per_iter);
  }


  virtual void dump(int indent = 0) {
    print_indent(indent);
    tty->print_cr("BINOP");
    _lhs->dump(indent + 1);
    _rhs->dump(indent + 1);
  }

  PatternInstance *operand(int with_opcode) {
    if (_lhs->op() == with_opcode) return _lhs;
    if (_rhs->op() == with_opcode) return _rhs;
    return NULL;
  }

  virtual PatternInstance *reduce(Node *reduction_phi, Node *iv);
};

struct LShiftPattern : BinOpPattern {
  LShiftPattern(int opcode, PatternInstance *lhs, PatternInstance *rhs, Node *origin)
    : BinOpPattern(opcode, lhs, rhs, origin) { }

  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                         Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt);
};

struct IntDemotionPattern : PatternInstance {
  const Type *_resulting_type;
  PatternInstance *_demoted;

  IntDemotionPattern(const Type *resulting_type, PatternInstance *demoted)
    : _resulting_type(resulting_type), _demoted(demoted) { }

  virtual int op() { return IntDemotion; }

  virtual BasicType velt() {
    return _resulting_type->array_element_basic_type();
  }

  virtual void dump(int indent = 0) {
    print_indent(indent);
    tty->print("INTDEMOTION "); // _resulting_type->dump();
    tty->print_cr("");
    _demoted->dump(indent + 1);
  }

  virtual AlignInfo *align_info(int vlen) { return _demoted->align_info(vlen); }

  virtual PatternInstance *reduce(Node *reduction_phi, Node *iv);
};

/****************************************************************
 * Higer-order patterns.
 ****************************************************************/
struct PrefixBinOpPattern : PatternInstance {
  PatternInstance *_prefix;
  PatternInstance *_c;

  PrefixBinOpPattern(PatternInstance *prefix, PatternInstance *c)
    : _prefix(prefix), _c(c) { }

  virtual BasicType velt() {
    return _prefix->velt();
  }

  virtual int op() { return PrefixBinOp; }
  virtual void dump(int indent) {
    print_indent(indent);
    tty->print("PrefixBinOp");
    _prefix->dump(indent + 1);
    _c->dump(indent + 1);
  }

  virtual AlignInfo *align_info(int vlen) { return _c->align_info(vlen); }

  virtual bool has_alignable_load() { return true; }
  virtual Node *base_addr() { return NULL; }

  static PrefixBinOpPattern *make(BinOpPattern *binop);
};

struct PrefixSumPattern : ArrayStorePattern {
  PrefixSumPattern(ArrayAccessPattern *access, PatternInstance *stored_value)
    : ArrayStorePattern(access, stored_value) {}

  PrefixBinOpPattern *stored_value() {
    assert(_stored_value->op() == PrefixBinOp,
           "reducing down to this node shall require"
           "the stored value to be a PrefixBinOp");
    return static_cast<PrefixBinOpPattern*>(_stored_value);
  }

  virtual int op() { return PrefixSum; }
  virtual Node *result() { return _access->result(); }
  virtual bool has_alignable_load() { return true; }
  virtual int memory_stride() { return _access->memory_stride(); }
  virtual BasicType elem_bt() { return _access->elem_bt(); }
  virtual Node *base_addr() { return _access->base_addr(); }
  virtual void dump(int indent = 0) {
    print_indent(indent);
    tty->print_cr("PREFIXSUM");
  }

  virtual AlignInfo *align_info(int vlen) { return _stored_value->align_info(vlen); }

  virtual bool is_rewritable_idiom() { return true; }
  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                         Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt);

  static PrefixSumPattern *make(ArrayStorePattern *p) {
    if (p->_stored_value->op() != PrefixBinOp) return NULL;
    if (p->velt() == T_LONG) return NULL;
    return new PrefixSumPattern(p->_access, p->_stored_value);
  }
};

struct ScaledReductionVariablePattern : PatternInstance {
  PatternInstance *_reduction_var;
  JavaValue _scale;
  ScaledReductionVariablePattern(PatternInstance *reduction_var, JavaValue scale)
    : _reduction_var(reduction_var), _scale(scale) { }

  virtual AlignInfo *align_info(int vlen) { return NULL; }
  virtual BasicType velt() { return _reduction_var->velt(); }
  virtual int op() { return ScaledReductionVariable; }
  virtual void dump(int indent=0) {
    print_indent(indent);
    tty->print_cr("SCALEDREDUCTIONVAR");
  }

  static ScaledReductionVariablePattern *make(BinOpPattern *p);
};

// Reduction on the form:
//   x = N*x + C
// (N: constant, C: expression not depending on x)
struct ReductionPattern : PatternInstance {
  // Opcode for the reducing binary operation.
  int _opcode;
  // x term.
  PatternInstance *_reduction_var;
  // C term.
  PatternInstance* _c;
  // Is the reduction variable scaled by `_n`?
  bool _scaled;
  // Constant multiplier N.
  JavaValue _n;
  // Vector element type.
  BasicType _velt;

  ReductionPattern(int opcode, PatternInstance *reduction_var,
                   PatternInstance *c, JavaValue n)
    : _opcode(opcode), _reduction_var(reduction_var),
      _c(c), _scaled(true), _n(n), _velt(reduction_var->velt()) { }

  ReductionPattern(int opcode, PatternInstance *reduction_var,
                   PatternInstance *c)
    : _opcode(opcode), _reduction_var(reduction_var),
      _c(c), _scaled(false), _velt(reduction_var->velt()) { }

  virtual AlignInfo *align_info(int vlen) { return _c->align_info(vlen); }

  virtual BasicType velt() {
    return _velt;
  }

  virtual int op() { return Reduction; };
  virtual void dump(int indent) {
    print_indent(indent);
    tty->print_cr("REDUCTION");
  };

  virtual bool is_rewritable_idiom() { return true; }
  virtual Node *generate(PhaseIdealLoop *phase, const Type *recurr_t, uint vlen,
                         Node *reduction_phi, Node *iv,
                         Node *loop_entry_ctrl,
                         Node_List& pre_old_new,
                         IdealLoopTree* lpt);

  static ReductionPattern *make(BinOpPattern *p);
  static ReductionPattern *make(IntDemotionPattern *p);
};

#endif
