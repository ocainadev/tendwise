"""Text normalisation shared by the input checks."""

import re
import unicodedata

# Different apostrophes customers' keyboards produce, all read as "'".
APOSTROPHES = str.maketrans({"’": "'", "‘": "'", "`": "'", "´": "'"})
SPACES_AND_TABS = re.compile(r"[ \t]+")


def normalize(text: str) -> str:
    """Lowercase, without accents or invisible characters, single spaces.

    Why: "Ignorá las INSTRUCCIONES" and "ignora las instrucciones" must hit the
    same pattern, and a zero-width space must not split a word to dodge it.
    Newlines are kept, because some role tags (`### system`) start a line.
    """
    # NFKD splits "é" into "e" + an accent mark (and turns full-width letters into
    # normal ones); we then drop the marks (Mn) and invisible format characters (Cf).
    decomposed = unicodedata.normalize("NFKD", text)
    kept = "".join(char for char in decomposed if unicodedata.category(char) not in ("Mn", "Cf"))
    return SPACES_AND_TABS.sub(" ", kept.translate(APOSTROPHES)).lower()
