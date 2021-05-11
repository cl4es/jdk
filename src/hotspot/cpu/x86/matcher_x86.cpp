/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "opto/addnode.hpp"
#include "opto/matcher.hpp"
#include "opto/regmask.hpp"

//=============================================================================
const bool Matcher::match_rule_supported(int opcode) {
  if (!has_match_rule(opcode)) {
    return false; // no match rule present
  }
  const bool is_LP64 = LP64_ONLY(true) NOT_LP64(false);
  switch (opcode) {
    case Op_AbsVL:
    case Op_StoreVectorScatter:
      if (UseAVX < 3) {
        return false;
      }
      break;
    case Op_PopCountI:
    case Op_PopCountL:
      if (!UsePopCountInstruction) {
        return false;
      }
      break;
    case Op_PopCountVI:
      if (!UsePopCountInstruction || !VM_Version::supports_avx512_vpopcntdq()) {
        return false;
      }
      break;
    case Op_MulVI:
      if ((UseSSE < 4) && (UseAVX < 1)) { // only with SSE4_1 or AVX
        return false;
      }
      break;
    case Op_MulVL:
      if (UseSSE < 4) { // only with SSE4_1 or AVX
        return false;
      }
      break;
    case Op_MulReductionVL:
      if (VM_Version::supports_avx512dq() == false) {
        return false;
      }
      break;
    case Op_AddReductionVL:
      if (UseSSE < 2) { // requires at least SSE2
        return false;
      }
      break;
    case Op_AbsVB:
    case Op_AbsVS:
    case Op_AbsVI:
    case Op_AddReductionVI:
    case Op_AndReductionV:
    case Op_OrReductionV:
    case Op_XorReductionV:
      if (UseSSE < 3) { // requires at least SSSE3
        return false;
      }
      break;
    case Op_VectorLoadShuffle:
    case Op_VectorRearrange:
    case Op_MulReductionVI:
      if (UseSSE < 4) { // requires at least SSE4
        return false;
      }
      break;
    case Op_SqrtVD:
    case Op_SqrtVF:
    case Op_VectorMaskCmp:
    case Op_VectorCastB2X:
    case Op_VectorCastS2X:
    case Op_VectorCastI2X:
    case Op_VectorCastL2X:
    case Op_VectorCastF2X:
    case Op_VectorCastD2X:
      if (UseAVX < 1) { // enabled for AVX only
        return false;
      }
      break;
    case Op_CompareAndSwapL:
#ifdef _LP64
    case Op_CompareAndSwapP:
#endif
      if (!VM_Version::supports_cx8()) {
        return false;
      }
      break;
    case Op_CMoveVF:
    case Op_CMoveVD:
      if (UseAVX < 1) { // enabled for AVX only
        return false;
      }
      break;
    case Op_StrIndexOf:
      if (!UseSSE42Intrinsics) {
        return false;
      }
      break;
    case Op_StrIndexOfChar:
      if (!UseSSE42Intrinsics) {
        return false;
      }
      break;
    case Op_OnSpinWait:
      if (VM_Version::supports_on_spin_wait() == false) {
        return false;
      }
      break;
    case Op_MulVB:
    case Op_LShiftVB:
    case Op_RShiftVB:
    case Op_URShiftVB:
    case Op_VectorInsert:
    case Op_VectorLoadMask:
    case Op_VectorStoreMask:
    case Op_VectorBlend:
      if (UseSSE < 4) {
        return false;
      }
      break;
#ifdef _LP64
    case Op_MaxD:
    case Op_MaxF:
    case Op_MinD:
    case Op_MinF:
      if (UseAVX < 1) { // enabled for AVX only
        return false;
      }
      break;
#endif
    case Op_CacheWB:
    case Op_CacheWBPreSync:
    case Op_CacheWBPostSync:
      if (!VM_Version::supports_data_cache_line_flush()) {
        return false;
      }
      break;
    case Op_ExtractB:
    case Op_ExtractL:
    case Op_ExtractI:
    case Op_RoundDoubleMode:
      if (UseSSE < 4) {
        return false;
      }
      break;
    case Op_RoundDoubleModeV:
      if (VM_Version::supports_avx() == false) {
        return false; // 128bit vroundpd is not available
      }
      break;
    case Op_LoadVectorGather:
      if (UseAVX < 2) {
        return false;
      }
      break;
    case Op_FmaVD:
    case Op_FmaVF:
      if (!UseFMA) {
        return false;
      }
      break;
    case Op_MacroLogicV:
      if (UseAVX < 3 || !UseVectorMacroLogic) {
        return false;
      }
      break;

    case Op_VectorMaskGen:
    case Op_LoadVectorMasked:
    case Op_StoreVectorMasked:
      if (!is_LP64  || UseAVX < 3 || !VM_Version::supports_bmi2()) {
        return false;
      }
      break;
#ifndef _LP64
    case Op_AddReductionVF:
    case Op_AddReductionVD:
    case Op_MulReductionVF:
    case Op_MulReductionVD:
      if (UseSSE < 1) { // requires at least SSE
        return false;
      }
      break;
    case Op_MulAddVS2VI:
    case Op_RShiftVL:
    case Op_AbsVD:
    case Op_NegVD:
      if (UseSSE < 2) {
        return false;
      }
      break;
#endif // !LP64
    case Op_SignumF:
      if (UseSSE < 1) {
        return false;
      }
      break;
    case Op_SignumD:
      if (UseSSE < 2) {
        return false;
      }
      break;
  }
  return true;  // Match rules are supported by default.
}

//------------------------------------------------------------------------

// Identify extra cases that we might want to provide match rules for vector nodes and
// other intrinsics guarded with vector length (vlen) and element type (bt).
const bool Matcher::match_rule_supported_vector(int opcode, int vlen, BasicType bt) {
  const bool is_LP64 = LP64_ONLY(true) NOT_LP64(false);
  if (!match_rule_supported(opcode)) {
    return false;
  }
  // Matcher::vector_size_supported() restricts vector sizes in the following way (see Matcher::vector_width_in_bytes):
  //   * SSE2 supports 128bit vectors for all types;
  //   * AVX1 supports 256bit vectors only for FLOAT and DOUBLE types;
  //   * AVX2 supports 256bit vectors for all types;
  //   * AVX512F supports 512bit vectors only for INT, FLOAT, and DOUBLE types;
  //   * AVX512BW supports 512bit vectors for BYTE, SHORT, and CHAR types.
  // There's also a limit on minimum vector size supported: 2 elements (or 4 bytes for BYTE).
  // And MaxVectorSize is taken into account as well.
  if (!vector_size_supported(bt, vlen)) {
    return false;
  }
  // Special cases which require vector length follow:
  //   * implementation limitations
  //   * some 512bit vector operations on FLOAT and DOUBLE types require AVX512DQ
  //   * 128bit vroundpd instruction is present only in AVX1
  int size_in_bits = vlen * type2aelembytes(bt) * BitsPerByte;
  switch (opcode) {
    case Op_AbsVF:
    case Op_NegVF:
      if ((vlen == 16) && (VM_Version::supports_avx512dq() == false)) {
        return false; // 512bit vandps and vxorps are not available
      }
      break;
    case Op_AbsVD:
    case Op_NegVD:
    case Op_MulVL:
      if ((vlen == 8) && (VM_Version::supports_avx512dq() == false)) {
        return false; // 512bit vpmullq, vandpd and vxorpd are not available
      }
      break;
    case Op_CMoveVF:
      if (vlen != 8) {
        return false; // implementation limitation (only vcmov8F_reg is present)
      }
      break;
    case Op_RotateRightV:
    case Op_RotateLeftV:
    case Op_MacroLogicV:
      if (!VM_Version::supports_evex() ||
          ((size_in_bits != 512) && !VM_Version::supports_avx512vl())) {
        return false;
      }
      break;
    case Op_ClearArray:
    case Op_VectorMaskGen:
    case Op_LoadVectorMasked:
    case Op_StoreVectorMasked:
      if (!is_LP64 || !VM_Version::supports_avx512bw()) {
        return false;
      }
      if ((size_in_bits != 512) && !VM_Version::supports_avx512vl()) {
        return false;
      }
      break;
    case Op_CMoveVD:
      if (vlen != 4) {
        return false; // implementation limitation (only vcmov4D_reg is present)
      }
      break;
    case Op_MaxV:
    case Op_MinV:
      if (UseSSE < 4 && is_integral_type(bt)) {
        return false;
      }
      if ((bt == T_FLOAT || bt == T_DOUBLE)) {
          // Float/Double intrinsics are enabled for AVX family currently.
          if (UseAVX == 0) {
            return false;
          }
          if (UseAVX > 2 && (!VM_Version::supports_avx512dq() && size_in_bits == 512)) { // 512 bit Float/Double intrinsics need AVX512DQ
            return false;
          }
      }
      break;
    case Op_AddReductionVI:
      if (bt == T_INT && (UseSSE < 3 || !VM_Version::supports_ssse3())) {
        return false;
      }
      // fallthrough
    case Op_AndReductionV:
    case Op_OrReductionV:
    case Op_XorReductionV:
      if (is_subword_type(bt) && (UseSSE < 4)) {
        return false;
      }
#ifndef _LP64
      if (bt == T_BYTE || bt == T_LONG) {
        return false;
      }
#endif
      break;
#ifndef _LP64
    case Op_VectorInsert:
      if (bt == T_LONG || bt == T_DOUBLE) {
        return false;
      }
      break;
#endif
    case Op_MinReductionV:
    case Op_MaxReductionV:
      if ((bt == T_INT || is_subword_type(bt)) && UseSSE < 4) {
        return false;
      } else if (bt == T_LONG && (UseAVX < 3 || !VM_Version::supports_avx512vlbwdq())) {
        return false;
      }
      // Float/Double intrinsics enabled for AVX family.
      if (UseAVX == 0 && (bt == T_FLOAT || bt == T_DOUBLE)) {
        return false;
      }
      if (UseAVX > 2 && (!VM_Version::supports_avx512dq() && size_in_bits == 512)) {
        return false;
      }
#ifndef _LP64
      if (bt == T_BYTE || bt == T_LONG) {
        return false;
      }
#endif
      break;
    case Op_VectorTest:
      if (UseSSE < 4) {
        return false; // Implementation limitation
      } else if (size_in_bits < 32) {
        return false; // Implementation limitation
      } else if (size_in_bits == 512 && (VM_Version::supports_avx512bw() == false)) {
        return false; // Implementation limitation
      }
      break;
    case Op_VectorLoadShuffle:
    case Op_VectorRearrange:
      if(vlen == 2) {
        return false; // Implementation limitation due to how shuffle is loaded
      } else if (size_in_bits == 256 && UseAVX < 2) {
        return false; // Implementation limitation
      } else if (bt == T_BYTE && size_in_bits > 256 && !VM_Version::supports_avx512_vbmi())  {
        return false; // Implementation limitation
      } else if (bt == T_SHORT && size_in_bits > 256 && !VM_Version::supports_avx512bw())  {
        return false; // Implementation limitation
      }
      break;
    case Op_VectorLoadMask:
      if (size_in_bits == 256 && UseAVX < 2) {
        return false; // Implementation limitation
      }
      // fallthrough
    case Op_VectorStoreMask:
      if (vlen == 2) {
        return false; // Implementation limitation
      }
      break;
    case Op_VectorCastB2X:
      if (size_in_bits == 256 && UseAVX < 2) {
        return false; // Implementation limitation
      }
      break;
    case Op_VectorCastS2X:
      if (is_integral_type(bt) && size_in_bits == 256 && UseAVX < 2) {
        return false;
      }
      break;
    case Op_VectorCastI2X:
      if (is_integral_type(bt) && size_in_bits == 256 && UseAVX < 2) {
        return false;
      }
      break;
    case Op_VectorCastL2X:
      if (is_integral_type(bt) && size_in_bits == 256 && UseAVX < 2) {
        return false;
      } else if (!is_integral_type(bt) && !VM_Version::supports_avx512dq()) {
        return false;
      }
      break;
    case Op_VectorCastF2X:
    case Op_VectorCastD2X:
      if (is_integral_type(bt)) {
        // Casts from FP to integral types require special fixup logic not easily
        // implementable with vectors.
        return false; // Implementation limitation
      }
    case Op_MulReductionVI:
      if (bt == T_BYTE && size_in_bits == 512 && !VM_Version::supports_avx512bw()) {
        return false;
      }
      break;
    case Op_StoreVectorScatter:
      if(bt == T_BYTE || bt == T_SHORT) {
        return false;
      } else if (size_in_bits < 512 && !VM_Version::supports_avx512vl()) {
        return false;
      }
      // fallthrough
    case Op_LoadVectorGather:
      if (size_in_bits == 64 ) {
        return false;
      }
      break;
  }
  return true;  // Per default match rules are supported.
}

const TypeVect* Matcher::predicate_reg_type(const Type* elemTy, int length) {
  return new TypeVectMask(TypeInt::BOOL, length);
}

const int Matcher::float_pressure(int default_pressure_threshold) {
  int float_pressure_threshold = default_pressure_threshold;
#ifdef _LP64
  if (UseAVX > 2) {
    // Increase pressure threshold on machines with AVX3 which have
    // 2x more XMM registers.
    float_pressure_threshold = default_pressure_threshold * 2;
  }
#endif
  return float_pressure_threshold;
}

// Max vector size in bytes. 0 if not supported.
const int Matcher::vector_width_in_bytes(BasicType bt) {
  assert(is_java_primitive(bt), "only primitive type vectors");
  if (UseSSE < 2) return 0;
  // SSE2 supports 128bit vectors for all types.
  // AVX2 supports 256bit vectors for all types.
  // AVX2/EVEX supports 512bit vectors for all types.
  int size = (UseAVX > 1) ? (1 << UseAVX) * 8 : 16;
  // AVX1 supports 256bit vectors only for FLOAT and DOUBLE.
  if (UseAVX > 0 && (bt == T_FLOAT || bt == T_DOUBLE))
    size = (UseAVX > 2) ? 64 : 32;
  if (UseAVX > 2 && (bt == T_BYTE || bt == T_SHORT || bt == T_CHAR))
    size = (VM_Version::supports_avx512bw()) ? 64 : 32;
  // Use flag to limit vector size.
  size = MIN2(size,(int)MaxVectorSize);
  // Minimum 2 values in vector (or 4 for bytes).
  switch (bt) {
  case T_DOUBLE:
  case T_LONG:
    if (size < 16) return 0;
    break;
  case T_FLOAT:
  case T_INT:
    if (size < 8) return 0;
    break;
  case T_BOOLEAN:
    if (size < 4) return 0;
    break;
  case T_CHAR:
    if (size < 4) return 0;
    break;
  case T_BYTE:
    if (size < 4) return 0;
    break;
  case T_SHORT:
    if (size < 4) return 0;
    break;
  default:
    ShouldNotReachHere();
  }
  return size;
}

// Limits on vector size (number of elements) loaded into vector.
const int Matcher::max_vector_size(const BasicType bt) {
  return vector_width_in_bytes(bt)/type2aelembytes(bt);
}
const int Matcher::min_vector_size(const BasicType bt) {
  int max_size = max_vector_size(bt);
  // Min size which can be loaded into vector is 4 bytes.
  int size = (type2aelembytes(bt) == 1) ? 4 : 2;
  return MIN2(size,max_size);
}

const int Matcher::scalable_vector_reg_size(const BasicType bt) {
  return -1;
}

// Vector ideal reg corresponding to specified size in bytes
const uint Matcher::vector_ideal_reg(int size) {
  assert(MaxVectorSize >= size, "");
  switch(size) {
    case  4: return Op_VecS;
    case  8: return Op_VecD;
    case 16: return Op_VecX;
    case 32: return Op_VecY;
    case 64: return Op_VecZ;
  }
  ShouldNotReachHere();
  return 0;
}

// Check for shift by small constant as well
static bool clone_shift(Node* shift, Matcher* matcher, Matcher::MStack& mstack, VectorSet& address_visited) {
  if (shift->Opcode() == Op_LShiftX && shift->in(2)->is_Con() &&
      shift->in(2)->get_int() <= 3 &&
      // Are there other uses besides address expressions?
      !matcher->is_visited(shift)) {
    address_visited.set(shift->_idx); // Flag as address_visited
    mstack.push(shift->in(2), Matcher::Visit);
    Node *conv = shift->in(1);
#ifdef _LP64
    // Allow Matcher to match the rule which bypass
    // ConvI2L operation for an array index on LP64
    // if the index value is positive.
    if (conv->Opcode() == Op_ConvI2L &&
        conv->as_Type()->type()->is_long()->_lo >= 0 &&
        // Are there other uses besides address expressions?
        !matcher->is_visited(conv)) {
      address_visited.set(conv->_idx); // Flag as address_visited
      mstack.push(conv->in(1), Matcher::Pre_Visit);
    } else
#endif
      mstack.push(conv, Matcher::Pre_Visit);
    return true;
  }
  return false;
}

// This function identifies sub-graphs in which a 'load' node is
// input to two different nodes, and such that it can be matched
// with BMI instructions like blsi, blsr, etc.
// Example : for b = -a[i] & a[i] can be matched to blsi r32, m32.
// The graph is (AndL (SubL Con0 LoadL*) LoadL*), where LoadL*
// refers to the same node.
//
// Match the generic fused operations pattern (op1 (op2 Con{ConType} mop) mop)
// This is a temporary solution until we make DAGs expressible in ADL.
template<typename ConType>
class FusedPatternMatcher {
  Node* _op1_node;
  Node* _mop_node;
  int _con_op;

  static int match_next(Node* n, int next_op, int next_op_idx) {
    if (n->in(1) == NULL || n->in(2) == NULL) {
      return -1;
    }

    if (next_op_idx == -1) { // n is commutative, try rotations
      if (n->in(1)->Opcode() == next_op) {
        return 1;
      } else if (n->in(2)->Opcode() == next_op) {
        return 2;
      }
    } else {
      assert(next_op_idx > 0 && next_op_idx <= 2, "Bad argument index");
      if (n->in(next_op_idx)->Opcode() == next_op) {
        return next_op_idx;
      }
    }
    return -1;
  }

 public:
  FusedPatternMatcher(Node* op1_node, Node* mop_node, int con_op) :
    _op1_node(op1_node), _mop_node(mop_node), _con_op(con_op) { }

  bool match(int op1, int op1_op2_idx,  // op1 and the index of the op1->op2 edge, -1 if op1 is commutative
             int op2, int op2_con_idx,  // op2 and the index of the op2->con edge, -1 if op2 is commutative
             typename ConType::NativeType con_value) {
    if (_op1_node->Opcode() != op1) {
      return false;
    }
    if (_mop_node->outcnt() > 2) {
      return false;
    }
    op1_op2_idx = match_next(_op1_node, op2, op1_op2_idx);
    if (op1_op2_idx == -1) {
      return false;
    }
    // Memory operation must be the other edge
    int op1_mop_idx = (op1_op2_idx & 1) + 1;

    // Check that the mop node is really what we want
    if (_op1_node->in(op1_mop_idx) == _mop_node) {
      Node* op2_node = _op1_node->in(op1_op2_idx);
      if (op2_node->outcnt() > 1) {
        return false;
      }
      assert(op2_node->Opcode() == op2, "Should be");
      op2_con_idx = match_next(op2_node, _con_op, op2_con_idx);
      if (op2_con_idx == -1) {
        return false;
      }
      // Memory operation must be the other edge
      int op2_mop_idx = (op2_con_idx & 1) + 1;
      // Check that the memory operation is the same node
      if (op2_node->in(op2_mop_idx) == _mop_node) {
        // Now check the constant
        const Type* con_type = op2_node->in(op2_con_idx)->bottom_type();
        if (con_type != Type::TOP && ConType::as_self(con_type)->get_con() == con_value) {
          return true;
        }
      }
    }
    return false;
  }
};

static bool is_bmi_pattern(Node* n, Node* m) {
  assert(UseBMI1Instructions, "sanity");
  if (n != NULL && m != NULL) {
    if (m->Opcode() == Op_LoadI) {
      FusedPatternMatcher<TypeInt> bmii(n, m, Op_ConI);
      return bmii.match(Op_AndI, -1, Op_SubI,  1,  0)  ||
             bmii.match(Op_AndI, -1, Op_AddI, -1, -1)  ||
             bmii.match(Op_XorI, -1, Op_AddI, -1, -1);
    } else if (m->Opcode() == Op_LoadL) {
      FusedPatternMatcher<TypeLong> bmil(n, m, Op_ConL);
      return bmil.match(Op_AndL, -1, Op_SubL,  1,  0) ||
             bmil.match(Op_AndL, -1, Op_AddL, -1, -1) ||
             bmil.match(Op_XorL, -1, Op_AddL, -1, -1);
    }
  }
  return false;
}

// Should the matcher clone input 'm' of node 'n'?
bool Matcher::pd_clone_node(Node* n, Node* m, Matcher::MStack& mstack) {
  // If 'n' and 'm' are part of a graph for BMI instruction, clone the input 'm'.
  if (UseBMI1Instructions && is_bmi_pattern(n, m)) {
    mstack.push(m, Visit);
    return true;
  }
  if (is_vshift_con_pattern(n, m)) { // ShiftV src (ShiftCntV con)
    mstack.push(m, Visit);           // m = ShiftCntV
    return true;
  }
  return false;
}

// Should the Matcher clone shifts on addressing modes, expecting them
// to be subsumed into complex addressing expressions or compute them
// into registers?
bool Matcher::pd_clone_address_expressions(AddPNode* m, Matcher::MStack& mstack, VectorSet& address_visited) {
  Node *off = m->in(AddPNode::Offset);
  if (off->is_Con()) {
    address_visited.test_set(m->_idx); // Flag as address_visited
    Node *adr = m->in(AddPNode::Address);

    // Intel can handle 2 adds in addressing mode
    // AtomicAdd is not an addressing expression.
    // Cheap to find it by looking for screwy base.
    if (adr->is_AddP() &&
        !adr->in(AddPNode::Base)->is_top() &&
        LP64_ONLY( off->get_long() == (int) (off->get_long()) && ) // immL32
        // Are there other uses besides address expressions?
        !is_visited(adr)) {
      address_visited.set(adr->_idx); // Flag as address_visited
      Node *shift = adr->in(AddPNode::Offset);
      if (!clone_shift(shift, this, mstack, address_visited)) {
        mstack.push(shift, Pre_Visit);
      }
      mstack.push(adr->in(AddPNode::Address), Pre_Visit);
      mstack.push(adr->in(AddPNode::Base), Pre_Visit);
    } else {
      mstack.push(adr, Pre_Visit);
    }

    // Clone X+offset as it also folds into most addressing expressions
    mstack.push(off, Visit);
    mstack.push(m->in(AddPNode::Base), Pre_Visit);
    return true;
  } else if (clone_shift(off, this, mstack, address_visited)) {
    address_visited.test_set(m->_idx); // Flag as address_visited
    mstack.push(m->in(AddPNode::Address), Pre_Visit);
    mstack.push(m->in(AddPNode::Base), Pre_Visit);
    return true;
  }
  return false;
}
