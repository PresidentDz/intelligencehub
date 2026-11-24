import { Extension, VueRenderer, type Editor } from '@tiptap/vue-3'
import Suggestion from '@tiptap/suggestion'
import { PluginKey } from '@tiptap/pm/state'
import { blockCommands, filterCommands, type BlockCommand } from './commands'
import SlashMenu from './SlashMenu.vue'

interface SlashOptions {
  insertImage: (editor: Editor) => void
}

/** Typing "/" opens a menu of block types, like Feishu Docs. */
export const SlashCommands = Extension.create<SlashOptions>({
  name: 'slashCommands',

  addOptions() {
    return { insertImage: () => {} }
  },

  addProseMirrorPlugins() {
    const commands = blockCommands(this.options.insertImage)
    return [
      Suggestion<BlockCommand, BlockCommand>({
        editor: this.editor,
        pluginKey: new PluginKey('slashCommands'),
        char: '/',
        startOfLine: false,
        allowSpaces: false,
        // Do not trigger inside code blocks, where "/" is ordinary text.
        allow: ({ editor }) => !editor.isActive('codeBlock'),
        items: ({ query }) => filterCommands(commands, query),
        command: ({ editor, range, props }) => props.run(editor as Editor, range),
        render: () => {
          let component: VueRenderer | null = null
          let unmount: (() => void) | undefined
          return {
            onStart: (props) => {
              component = new VueRenderer(SlashMenu, { props, editor: props.editor as Editor })
              if (component.element) unmount = props.mount(component.element as HTMLElement)
            },
            onUpdate: (props) => component?.updateProps(props),
            onKeyDown: ({ event }) => (event.key === 'Escape' ? false : component?.ref?.onKeyDown(event) ?? false),
            onExit: () => {
              unmount?.()
              component?.destroy()
              component = null
            },
          }
        },
      }),
    ]
  },
})
