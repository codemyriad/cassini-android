"""Check APK architectures and native layout; this does not certify runtime support."""
import struct
import zipfile

PAGE_SIZE = 16 * 1024
MACHINES = {'arm64-v8a': 183, 'x86_64': 62}


def check_apk_compatibility(path):
    """Reject native binaries that cannot load on a supported 16 KB system."""
    with zipfile.ZipFile(path) as apk, open(path, 'rb') as raw:
        libraries = [entry for entry in apk.infolist()
                     if entry.filename.startswith('lib/') and entry.filename.endswith('.so')]
        architectures = {entry.filename.split('/')[1] for entry in libraries}
        if architectures != set(MACHINES):
            raise ValueError(f'Unexpected APK architectures: {architectures}')
        for entry in libraries:
            name = entry.filename
            architecture = name.split('/')[1]
            data = apk.read(entry)
            if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
                raise ValueError(f'{name}: expected a little-endian 64-bit ELF library')
            machine, = struct.unpack_from('<H', data, 18)
            if machine != MACHINES[architecture]:
                raise ValueError(f'{name}: ELF architecture does not match its APK directory')
            table, = struct.unpack_from('<Q', data, 32)
            entry_size, count = struct.unpack_from('<HH', data, 54)
            if entry_size < 56 or count == 0 or table + entry_size * count > len(data):
                raise ValueError(f'{name}: invalid ELF program headers')
            loads = 0
            for index in range(count):
                kind, _, offset, address, _, _, _, alignment = struct.unpack_from(
                    '<IIQQQQQQ', data, table + index * entry_size)
                if kind == 1:  # PT_LOAD
                    loads += 1
                    if (alignment < PAGE_SIZE or alignment & (alignment - 1)
                            or offset % PAGE_SIZE != address % PAGE_SIZE):
                        raise ValueError(f'{name}: load segment is not 16 KB aligned')
            if not loads:
                raise ValueError(f'{name}: no ELF load segments')
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                if header[:4] != b'PK\x03\x04':
                    raise ValueError(f'{name}: invalid ZIP local header')
                filename_size, extra_size = struct.unpack_from('<HH', header, 26)
                data_offset = entry.header_offset + 30 + filename_size + extra_size
                if data_offset % PAGE_SIZE:
                    raise ValueError(f'{name}: uncompressed library is not 16 KB ZIP aligned')
    return len(libraries)
