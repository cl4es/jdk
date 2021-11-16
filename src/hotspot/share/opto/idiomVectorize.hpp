#ifndef SHARE_OPTO_POLYNOMIALREDUCTION_HPP
#define SHARE_OPTO_POLYNOMIALREDUCTION_HPP

//#include "precompiled.hpp"
#include "utilities/globalDefinitions.hpp"

class IdealLoopTree;
class Compile;
class PhaseIdealLoop;
class PhaseIterGVN;
class Node;
class Type;
class CountedLoopNode;
class PhiNode;

// Trace options.
enum TraceOpts {
  NoTraceOpts = 0,
  MinCond = 1 << 0,
  Match = 1 << 1,
  Rewrite = 1 << 2,
  Success = 1 << 3,
  Failure = 1 << 4,
  FinalReport = Success | Failure,
  Candidates = 1 << 5,
  AllTraceOpts = 0xFFFFFFFF
};

// Enabled trace options.
extern const int TRACE_OPTS;

#ifndef PRODUCT
#define TRACE(OPT, BODY)                                                \
  do {                                                                  \
    if (((OPT) & TRACE_OPTS) != 0) {                                    \
      BODY;                                                             \
    }                                                                   \
  } while(0)
#else
#define TRACE(OPT, BODY)                        \
  do { } while (0)
#endif
JavaValue make_pow(JavaValue n, jint exp, BasicType bt);
Node *identity_con(int opc);
int add_opcode(BasicType bt);
int mul_opcode(BasicType bt);
bool is_associative(int opc);
bool is_semiassociative(int opc);
int reduction_opcode(int opc);

bool idiom_analyze(Compile *C, PhaseIdealLoop *phase, PhaseIterGVN *igvn, IdealLoopTree *lpt);

Node *make_exp_vector(PhaseIdealLoop *phase, JavaValue n, juint vlen, const Type *t,
                      Node *control);
Node *make_vector(PhaseIdealLoop *phase, Node *init, const Type *recurr_t,
                  juint vec_size, Node *control=NULL);
Node *make_vector(PhaseIdealLoop *phase, jlong init, const Type *recurr_t,
                  juint vec_size, Node *control=NULL);
Node *make_vector(PhaseIdealLoop *phase, JavaValue init, const Type *recurr_t, juint vec_size,
                  Node *control);
void adjust_limit(CountedLoopNode *cl, PhaseIterGVN &igvn, Node *adjusted_limit);

PhiNode *find_recurrence_phi(CountedLoopNode *cl, bool memory=false);

#endif
