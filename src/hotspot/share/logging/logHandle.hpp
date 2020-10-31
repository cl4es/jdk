/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates. All rights reserved.
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
#ifndef SHARE_LOGGING_LOGHANDLE_HPP
#define SHARE_LOGGING_LOGHANDLE_HPP

#include "logging/log.hpp"

// Wraps a LogTarget instance and throws away the template information.
//
// This can be used to pass a Log instance as a parameter without
// polluting the surrounding API with template functions.
class LogTargetHandle {
private:
  const LogLevelType _level;
  LogTagSet*         _tagset;

public:
  LogTargetHandle(LogLevelType level, LogTagSet* tagset) : _level(level), _tagset(tagset) {}

  template <LogLevelType level, LogTagType T0, LogTagType T1, LogTagType T2, LogTagType T3, LogTagType T4, LogTagType GuardTag>
  LogTargetHandle(const LogTargetImpl<level, T0, T1, T2, T3, T4, GuardTag>& type_carrier) :
      _level(level),
      _tagset(&LogTagSetMapping<T0, T1, T2, T3, T4>::tagset()) {}

  template <LogLevelType level, LogTagType T0, LogTagType T1, LogTagType T2, LogTagType T3, LogTagType T4, LogTagType GuardTag>
  static LogTargetHandle create() {
    return LogTargetHandle(LogTargetImpl<level, T0, T1, T2, T3, T4, GuardTag>());
  }

  void print(const char* fmt, ...) ATTRIBUTE_PRINTF(2, 3) {
    va_list args;
    va_start(args, fmt);
    _tagset->vwrite(_level, fmt, args);
    va_end(args);
  }

  bool is_enabled() const {
    return _tagset->is_level(_level);
  }

};

#endif // SHARE_LOGGING_LOGHANDLE_HPP
