import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { InkDocument } from '../model/InkModel';

const NOTE_FILE_NAME = 'padnote-current.json';

export class NoteStore {
  static load(context: common.UIAbilityContext): InkDocument | undefined {
    const path = `${context.filesDir}/${NOTE_FILE_NAME}`;
    try {
      if (!fileIo.accessSync(path)) {
        return undefined;
      }
      const source = fileIo.readTextSync(path);
      if (source.length === 0) {
        return undefined;
      }
      return JSON.parse(source) as InkDocument;
    } catch (error) {
      console.error(`PadNote load failed: ${JSON.stringify(error)}`);
      return undefined;
    }
  }

  static save(context: common.UIAbilityContext, document: InkDocument): boolean {
    const path = `${context.filesDir}/${NOTE_FILE_NAME}`;
    let file: fileIo.File | undefined = undefined;
    try {
      file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE);
      fileIo.truncateSync(file.fd, 0);
      fileIo.writeSync(file.fd, JSON.stringify(document));
      fileIo.fsyncSync(file.fd);
      return true;
    } catch (error) {
      console.error(`PadNote save failed: ${JSON.stringify(error)}`);
      return false;
    } finally {
      if (file !== undefined) {
        fileIo.closeSync(file);
      }
    }
  }
}
