import vue from '@vitejs/plugin-vue';

import createUnoCss from './unocss.ts';
import createAutoImport from './auto-import.ts';
import createComponents from './components.ts';
import createSvgIconsPlugin from './svg-icon.ts';
import createCompression from './compression.ts';
import createSetupExtend from './setup-extend.ts';
import { viteCheckTransitionPlugin } from './check-transition.ts';

export default (viteEnv: any, isBuild = false): [] => {
  const vitePlugins: any = [];
  vitePlugins.push(vue());
  vitePlugins.push(createUnoCss());
  vitePlugins.push(createAutoImport());
  vitePlugins.push(createComponents());
  vitePlugins.push(createCompression(viteEnv));
  vitePlugins.push(createSvgIconsPlugin());
  vitePlugins.push(createSetupExtend());
  vitePlugins.push(viteCheckTransitionPlugin())
  return vitePlugins;
};
