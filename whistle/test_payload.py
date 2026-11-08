"""The ABI model container carries data with no executable segment or dynamic loader metadata."""

import importlib.util
import struct
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'whistle_payload', Path(__file__).resolve().parents[1] / 'scripts/whistle_payload.py',
)
PAYLOAD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAYLOAD)


class PayloadTests(unittest.TestCase):
    def test_read_only_container_preserves_exact_models_at_page_offsets(self):
        model, vad = b'pinned model', b'pinned VAD'
        output = PAYLOAD.payload(model, vad)
        model_at, vad_at = PAYLOAD.model_offsets(len(model))
        self.assertEqual(output[model_at:model_at + len(model)], model)
        self.assertEqual(output[vad_at:], vad)
        self.assertEqual(model_at % 16384, 0)
        self.assertEqual(vad_at % 16384, 0)
        self.assertEqual(output[:7], b'\x7fELF\x02\x01\x01')
        header = struct.unpack_from('<16sHHIQQQIHHHHHH', output)
        self.assertEqual(header[2], 183)  # ARM64
        self.assertEqual(header[5], 64)  # Only program headers, no section table.
        self.assertEqual(header[6], 0)
        self.assertEqual(header[10], 1)  # A single segment.
        segment = struct.unpack_from('<IIQQQQQQ', output, 64)
        self.assertEqual(segment, (1, 4, 0, 0, 0, len(output), len(output), 16384))
        self.assertEqual(PAYLOAD.payload(model, vad), output)


if __name__ == '__main__':
    unittest.main()
