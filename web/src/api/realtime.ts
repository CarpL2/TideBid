import { requestData } from '@/api/http'
import type { ApiResult } from '@/types/api'
import type { RealtimeTicketIssue } from '@/types/realtime'

export function issueRealtimeTicket(): Promise<ApiResult<RealtimeTicketIssue>> {
  return requestData<RealtimeTicketIssue>({
    method: 'POST',
    url: '/realtime/tickets',
  })
}

