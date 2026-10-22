import { queryOptions, useQuery } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { getCurrentUser } from './api'

const subscribe = () => () => {}
const browserSnapshot = () => true
const serverSnapshot = () => false

export function currentUserOptions() {
  return queryOptions({
    queryKey: ['auth', 'me'],
    queryFn: ({ signal }) => getCurrentUser(signal),
    retry: false,
  })
}

export function useCurrentUser() {
  const hydrated = useSyncExternalStore(
    subscribe,
    browserSnapshot,
    serverSnapshot,
  )
  return useQuery({ ...currentUserOptions(), enabled: hydrated })
}
