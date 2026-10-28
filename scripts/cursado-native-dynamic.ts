import { createElement, lazy, Suspense, type ComponentType } from 'react'

type LoadedComponent = ComponentType<any> | { default: ComponentType<any> }

// next/dynamic accepts both import() modules and loaders selecting a named export.
export default function dynamic(loader: () => Promise<LoadedComponent>, _options?: unknown) {
  const Component = lazy(async () => {
    const loaded = await loader()
    return { default: 'default' in loaded ? loaded.default : loaded }
  })
  return function Dynamic(props: any) {
    return createElement(Suspense, { fallback: null }, createElement(Component, props))
  }
}
