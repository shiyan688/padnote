import SwiftUI

struct NewNoteView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var paper = "ruled"
    @State private var ratio = "a4"
    @State private var landscape = false
    var onCreate: (NoteDocument) -> Void

    private var style: PageStyle { PageStyle(paper: paper, ratio: ratio, landscape: landscape) }

    var body: some View {
        NavigationStack {
            Form {
                Section("笔记名称") {
                    TextField("未命名笔记", text: $title).accessibilityIdentifier("noteTitleField")
                }
                Section {
                    Picker("底纹", selection: $paper) {
                        Text("白纸").tag("blank"); Text("横线").tag("ruled")
                        Text("方格").tag("grid"); Text("点阵").tag("dotted")
                    }.pickerStyle(.segmented)
                    Picker("比例", selection: $ratio) {
                        Text("A4").tag("a4"); Text("平板").tag("screen")
                    }
                    Toggle("横向纸张", isOn: $landscape)
                    HStack {
                        Spacer()
                        PaperPreview(style: style)
                            .frame(width: landscape ? 224 : 160, height: landscape ? 160 : 224)
                            .overlay(Rectangle().stroke(PadTheme.border))
                        Spacer()
                    }.padding(.vertical, 12)
                } header: { Text("纸张") } footer: {
                    Text("创建后保持纸张尺寸，确保手写笔迹在不同设备上位置一致。")
                }
            }
            .navigationTitle("新建笔记")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        let clean = title.trimmingCharacters(in: .whitespacesAndNewlines)
                        var note = NoteDocument(title: clean.isEmpty ? "未命名笔记" : clean, pageStyle: style)
                        let short: Double = 768
                        let long: Double = ratio == "a4" ? short * sqrt(2) : 1024
                        note.pageWidth = landscape ? long : short
                        note.pageHeight = landscape ? short : long
                        onCreate(note); dismiss()
                    }.fontWeight(.semibold).accessibilityIdentifier("createNoteConfirm")
                }
            }
        }.presentationDetents([.large])
    }
}
