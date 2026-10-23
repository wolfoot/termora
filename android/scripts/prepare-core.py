#!/usr/bin/env python3
"""Build the desktop's platform-independent terminal and wire models for Android.
Replacements are guarded so changes in upstream desktop code fail visibly.
"""
from pathlib import Path
import sys, shutil, re
root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]); shutil.rmtree(out, ignore_errors=True)
source = root / 'src/main/kotlin/app/termora'
def emit(rel, text):
    dest = out / 'app/termora' / rel; dest.parent.mkdir(parents=True, exist_ok=True); dest.write_text(text)
def replace(text, old, new):
    assert old in text, f'Portable adaptation out of date: {old}'
    return text.replace(old, new)
for f in (source/'terminal').glob('*.kt'):
    if f.name == 'PtyProcessConnector.kt': continue
    text = f.read_text()
    if f.name == 'ColorPaletteImpl.kt':
        text = replace(text, 'import app.termora.ApplicationScope', '')
        text = replace(text, 'import java.awt.Color', '')
        text = replace(text, 'ApplicationScope.forApplicationScope().getOrCreate(DefaultColorTheme::class) { DefaultColorTheme() }', 'DefaultColorTheme()')
        text = replace(text, 'Color.black.rgb', '0')
    if f.name == 'TerminalModelImpl.kt':
        text = replace(text, 'import java.awt.Toolkit', '')
        text = replace(text, 'Toolkit.getDefaultToolkit().beep()', 'app.termora.TerminalPlatform.bell()')
    if f.name == 'OperatingSystemCommandProcessor.kt':
        text = replace(text, 'import java.awt.Toolkit', '')
        text = replace(text, 'import java.awt.datatransfer.StringSelection', '')
        text = re.sub(r'Toolkit.getDefaultToolkit\(\).systemClipboard.setContents\(StringSelection\((.*?)\), null\)', r'app.termora.TerminalPlatform.clipboard(\1)', text)
    if f.name == 'KeyEncoderImpl.kt':
        text = replace(text, 'import com.formdev.flatlaf.util.SystemInfo', '')
        text = replace(text, 'import java.awt.event.KeyEvent', 'import app.termora.KeyCodes as KeyEvent')
        text = replace(text, 'SystemInfo.isMacOS', 'false')
    if f.name == 'TerminalLineBuffer.kt':
        text = text.replace('override fun addFirst', 'fun addFirst').replace('override fun addLast', 'fun addLast').replace('super.addFirst(element)', 'super.add(0, element)').replace('super.addLast(element)', 'super.add(element)')
    emit('terminal/'+f.name, text)
for rel in ['Host.kt', 'SyncEncryption.kt', 'ShellArguments.kt', 'tag/Tag.kt', 'snippet/Snippet.kt', 'keymgr/OhKeyPair.kt', 'macro/Macro.kt', 'plugin/internal/AltKeyModifier.kt']:
    emit(rel, (source/rel).read_text())
t = (source/'terminal/panel/TerminalWriter.kt').read_text().replace('import javax.swing.JComponent', '').replace('    fun onMounted(c: JComponent)', '')
emit('terminal/panel/TerminalWriter.kt', t)
# Constants have the same values as desktop Java KeyEvent; no AWT classes enter the APK.
keys = {'BACK_SPACE':8,'TAB':9,'ENTER':10,'ESCAPE':27,'SPACE':32,'PAGE_UP':33,'PAGE_DOWN':34,'END':35,'HOME':36,'LEFT':37,'UP':38,'RIGHT':39,'DOWN':40,'DELETE':127,'INSERT':155,'SHIFT':16,'CONTROL':17,'ALT':18,'META':157,'NUM_LOCK':144,'CLEAR':12,'ADD':107,'SUBTRACT':109,'MULTIPLY':106,'DIVIDE':111,'DECIMAL':110,'SEPARATOR':108,'EQUALS':61,'MINUS':45,'COMMA':44,'PERIOD':46,'SLASH':47,'BACK_SLASH':92,'OPEN_BRACKET':91,'CLOSE_BRACKET':93,'BACK_QUOTE':192,'QUOTE':222,'KP_UP':224,'KP_DOWN':225,'KP_LEFT':226,'KP_RIGHT':227}
keys.update({chr(n):n for n in range(65,91)}); keys.update({str(n):48+n for n in range(10)})
keys.update({f'NUMPAD{n}':96+n for n in range(10)}); keys.update({f'F{n}':111+n if n<=12 else 61440+n-13 for n in range(1,25)})
used = set(re.findall(r'KeyEvent\.VK_(\w+)', (source/'terminal/KeyEncoderImpl.kt').read_text()))
assert used <= keys.keys(), f'Unknown keys: {used - keys.keys()}'
emit('KeyCodes.kt', 'package app.termora\nobject KeyCodes {\n' + '\n'.join(f' const val VK_{k} = {v}' for k,v in keys.items())+'\n}')
assert not any(re.search(r'import (java.awt|javax.swing|com.formdev)', p.read_text()) for p in out.rglob('*.kt'))
