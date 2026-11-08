"""Deterministic read-only ELF data container: ABI delivery only; never dlopen or execute it."""

import struct

PAGE = 16384
HEADER_BYTES = 64
PROGRAM_BYTES = 56


def model_offsets(model_size):
    return PAGE, ((PAGE + model_size + PAGE - 1) // PAGE) * PAGE


def payload(model, vad):
    model_at, vad_at = model_offsets(len(model))
    size = vad_at + len(vad)
    result = bytearray(size)
    ident = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<16sHHIQQQIHHHHHH', result, 0, ident, 3, 183, 1, 0, HEADER_BYTES, 0, 0,
                     HEADER_BYTES, PROGRAM_BYTES, 1, 0, 0, 0)
    # One read-only PT_LOAD, no executable segment, dynamic table, symbols or initializers.
    struct.pack_into('<IIQQQQQQ', result, HEADER_BYTES, 1, 4, 0, 0, 0, size, size, PAGE)
    result[model_at:model_at + len(model)] = model
    result[vad_at:vad_at + len(vad)] = vad
    return result
