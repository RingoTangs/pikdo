import tailwindcss from '@tailwindcss/vite'
import { tanstackStart } from '@tanstack/react-start/plugin/vite'
import react from '@vitejs/plugin-react'
import { nitro } from 'nitro/vite'
import { defineConfig } from 'vite'

const PORT = 3000

export default defineConfig({
  resolve: { tsconfigPaths: true },
  plugins: [
    tailwindcss(),
    tanstackStart(),
    nitro({
      rolldownConfig: {
        onLog(level, log, defaultHandler) {
          const source = (log.id ?? log.loc?.file ?? log.message).replace(
            /\\/g,
            '/',
          )
          // These dependency directives are not needed by the current SSR bundle.
          if (
            level === 'warn' &&
            log.code === 'MODULE_LEVEL_DIRECTIVE' &&
            log.message.includes('"use client"') &&
            source.includes('node_modules/@tanstack/')
          ) {
            return
          }
          defaultHandler(level, log)
        },
      },
    }),
    react(),
  ],
  server: {
    port: PORT,
  },
  preview: {
    port: PORT,
  },
})
