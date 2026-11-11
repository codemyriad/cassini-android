"""Plain Play release notes, limited in UTF-16 units without splitting supplementary characters."""


def utf16_units(text: str) -> int:
    return len(text.encode('utf-16-le')) // 2


def format_play_notes(text: str) -> str:
    text = text.replace('<', '[').replace('>', ']')
    if utf16_units(text) <= 500:
        return text
    result, used = [], 0
    for char in text:
        width = 2 if ord(char) > 0xFFFF else 1
        if used + width > 497:
            break
        result.append(char)
        used += width
    return ''.join(result) + '...'
