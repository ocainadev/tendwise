// The logged-in user, kept in the TanStack Query cache under ME_QUERY_KEY.
// Every page reads it from there, so login and logout only have to update
// that one cache entry.

import { QueryCache, QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { fetchMe, login, logout, type Me, type Membership } from '../api/auth'

export const ME_QUERY_KEY = ['auth', 'me']

export function createQueryClient(): QueryClient {
  const queryClient: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      // A 401 that survived the automatic refresh means the session ended:
      // forget the user, so RequireAuth sends them to the login page.
      onError: (error) => {
        if (error instanceof ApiError && error.status === 401) {
          queryClient.setQueryData(ME_QUERY_KEY, null)
        }
      },
    }),
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
    },
  })
  return queryClient
}

export function useMe() {
  return useQuery({ queryKey: ME_QUERY_KEY, queryFn: fetchMe, staleTime: 5 * 60 * 1000 })
}

// For pages under RequireAuth, which renders them only once the user is known.
export function useCurrentUser(): Me {
  const { data } = useMe()
  if (!data) {
    throw new Error('useCurrentUser() is only valid inside <RequireAuth>')
  }
  return data
}

// The membership for the :slug in the URL, or undefined if the user is not a
// member of that business.
export function useMembership(): Membership | undefined {
  const { slug } = useParams()
  return useCurrentUser().memberships.find((membership) => membership.slug === slug)
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password),
    onSuccess: (me) => queryClient.setQueryData(ME_QUERY_KEY, me),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  return useMutation({
    mutationFn: logout,
    onSettled: () => {
      // Go to the login page first, so it does not offer to bring the next
      // user back to this page. Then drop everything cached for this user
      // and mark them logged out.
      navigate('/login', { replace: true })
      queryClient.clear()
      queryClient.setQueryData(ME_QUERY_KEY, null)
    },
  })
}

// Where a user lands after login when they did not come from a page.
export function homePath(me: Me): string {
  if (me.platformRole === 'ADMIN') {
    return '/admin'
  }
  if (me.memberships.length === 1) {
    return `/app/${me.memberships[0].slug}`
  }
  // Several businesses (or none): the chooser page.
  return '/app'
}
