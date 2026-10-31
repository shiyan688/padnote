Unicode True
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"

!define MUI_ICON "${__FILEDIR__}PadNote.ico"
!define MUI_UNICON "${__FILEDIR__}PadNote.ico"

!ifndef STAGE
  !define STAGE "${__FILEDIR__}build/stage"
!endif
!ifndef PRODUCT_VERSION
  !define PRODUCT_VERSION "0.18.0-beta.37"
!endif
!ifndef FILE_VERSION
  !define FILE_VERSION "0.18.0.37"
!endif
!ifndef OUTFILE
  !define OUTFILE "${__FILEDIR__}build/PadNote-Connection-Assistant-${PRODUCT_VERSION}-Setup-x64.exe"
!endif

Name "PadNote Connection Assistant ${PRODUCT_VERSION}"
Caption "PadNote 连接助手安装程序"
Icon "${__FILEDIR__}PadNote.ico"
UninstallIcon "${__FILEDIR__}PadNote.ico"
OutFile "${OUTFILE}"
VIProductVersion "${FILE_VERSION}"
InstallDir "$LOCALAPPDATA\Programs\PadNote\Connection Assistant"
RequestExecutionLevel user
ShowInstDetails show
ShowUnInstDetails show
SetCompressor /SOLID lzma
SetCompressorDictSize 32

!define PRODUCT "PadNote Connection Assistant"
!define REGKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\PadNoteConnectionAssistant"
!define OWNER_ID "PadNote-Connection-Assistant"
!define WINDOW_CLASS "PadNoteConnectionAssistantWindow"
!define WINDOW_TITLE "PadNote Connection Assistant"
!define WINDOW_PROP "PadNoteConnectionAssistantOwnedWindow"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN ""
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchAssistant
!define MUI_FINISHPAGE_RUN_TEXT "启动 PadNote 连接助手"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "ProductName" "PadNote Connection Assistant"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "FileVersion" "${FILE_VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "FileDescription" "PadNote Connection Assistant Setup"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "LegalCopyright" "PadNote"

!include "${STAGE}\installer-payload.nsh"

Function LaunchAssistant
  Exec '"$INSTDIR\runtime\pythonw.exe" "$INSTDIR\launcher.py"'
FunctionEnd

!macro CLOSE_OWNED_LAUNCHER
  System::Call 'user32::FindWindowW(w "${WINDOW_CLASS}", w "${WINDOW_TITLE}") p.r0'
  ${If} $0 != 0
    System::Call 'user32::GetPropW(p $0, w "${WINDOW_PROP}") p.r1'
    ${If} $1 != 1
      MessageBox MB_ICONSTOP|MB_OK "检测到同名窗口，但无法确认它属于本安装程序。请先手动关闭窗口后重试。"
      Abort
    ${EndIf}
    ; WM_CLOSE runs graceful shutdown, which can take up to 13 seconds. Post
    ; asynchronously, then verify the owned window disappears before touching files.
    System::Call 'user32::PostMessageW(p $0, i 0x0010, p 0, p 0)'
    StrCpy $2 0
    close_wait:
      Sleep 250
      System::Call 'user32::FindWindowW(w "${WINDOW_CLASS}", w "${WINDOW_TITLE}") p.r0'
      ${If} $0 == 0
        Goto close_done
      ${EndIf}
      System::Call 'user32::GetPropW(p $0, w "${WINDOW_PROP}") p.r1'
      ${If} $1 != 1
        MessageBox MB_ICONSTOP|MB_OK "检测到同名窗口，但无法确认它属于本安装程序。为保护其他程序，安装或卸载已取消。"
        Abort
      ${EndIf}
      IntOp $2 $2 + 1
      ${If} $2 >= 60
        MessageBox MB_ICONSTOP|MB_OK "连接助手在 15 秒内没有关闭，无法安全替换或卸载文件。请稍后重试，或先在连接助手中停止服务并关闭窗口。"
        Abort
      ${EndIf}
      Goto close_wait
    close_done:
  ${EndIf}
!macroend

Function .onInit
  SetShellVarContext current
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "此安装程序仅支持 64 位 Windows。"
    Abort
  ${EndIf}
  SetRegView 64
  ; Never adopt or overwrite a nonempty directory we did not create.
  IfFileExists "$INSTDIR\.padnote-owner.ini" owner_check empty_check
  owner_check:
    ReadINIStr $1 "$INSTDIR\.padnote-owner.ini" "PadNote" "Id"
    ${If} $1 != "${OWNER_ID}"
      MessageBox MB_ICONSTOP|MB_OK "安装目录标记无效。为保护已有文件，安装已取消。"
      Abort
    ${EndIf}
    Goto close_launcher
  empty_check:
    FindFirst $0 $1 "$INSTDIR\*"
  empty_loop:
    ${If} $1 == ""
      Goto empty_done
    ${EndIf}
    ${If} $1 != "."
    ${AndIf} $1 != ".."
      FindClose $0
      MessageBox MB_ICONSTOP|MB_OK "默认安装目录已包含其他文件。为保护已有数据，请先移走文件或卸载该目录中的旧版本。"
      Abort
    ${EndIf}
    FindNext $0 $1
    Goto empty_loop
  empty_done:
    FindClose $0
  close_launcher:
    !insertmacro CLOSE_OWNED_LAUNCHER
FunctionEnd

Function un.onInit
  SetShellVarContext current
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "此安装程序仅支持 64 位 Windows。"
    Abort
  ${EndIf}
  SetRegView 64
  ReadINIStr $1 "$INSTDIR\.padnote-owner.ini" "PadNote" "Id"
  ${If} $1 != "${OWNER_ID}"
    MessageBox MB_ICONSTOP|MB_OK "安装目录所有权标记无效。为保护已有文件，卸载已取消。"
    Abort
  ${EndIf}
  !insertmacro CLOSE_OWNED_LAUNCHER
FunctionEnd

Section "PadNote 连接助手（必需）" SEC_MAIN
  SectionIn RO
  SetShellVarContext current
  SetRegView 64
  SetOutPath "$INSTDIR"
  WriteINIStr "$INSTDIR\.padnote-owner.ini" "PadNote" "Id" "${OWNER_ID}"
  File /r "${STAGE}\*"
  File "${__FILEDIR__}PadNote.ico"
  CreateDirectory "$SMPROGRAMS\PadNote"
  CreateShortCut "$SMPROGRAMS\PadNote\PadNote Connection Assistant.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\launcher.py"' "$INSTDIR\PadNote.ico"
  CreateShortCut "$SMPROGRAMS\PadNote\Uninstall.lnk" "$INSTDIR\Uninstall.exe"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "${REGKEY}" "DisplayName" "${PRODUCT}"
  WriteRegStr HKCU "${REGKEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKCU "${REGKEY}" "Publisher" "PadNote"
  WriteRegStr HKCU "${REGKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${REGKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${REGKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${REGKEY}" "NoRepair" 1
SectionEnd

Section /o "桌面快捷方式（可选）" SEC_DESKTOP
  SetShellVarContext current
  CreateShortCut "$DESKTOP\PadNote Connection Assistant.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\launcher.py"' "$INSTDIR\PadNote.ico"
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  SetRegView 64
  Delete "$DESKTOP\PadNote Connection Assistant.lnk"
  Delete "$SMPROGRAMS\PadNote\PadNote Connection Assistant.lnk"
  Delete "$SMPROGRAMS\PadNote\Uninstall.lnk"
  RMDir "$SMPROGRAMS\PadNote"
  DeleteRegKey HKCU "${REGKEY}"
  !insertmacro UNINSTALL_PAYLOAD
  Delete /REBOOTOK "$INSTDIR\Uninstall.exe"
  Delete /REBOOTOK "$INSTDIR\.padnote-owner.ini"
  Delete /REBOOTOK "$INSTDIR\PadNote.ico"
  RMDir /REBOOTOK "$INSTDIR"
  ; User data in %USERPROFILE%\.local\share\padnote-connection-assistant is preserved.
SectionEnd
