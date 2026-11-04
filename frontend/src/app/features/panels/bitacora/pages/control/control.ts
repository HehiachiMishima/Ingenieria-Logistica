import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Icon } from '../../../../../shared/ui/icon/icon';

@Component({
  selector: 'app-bitacora-control',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon],
  templateUrl: './control.html',
  styleUrl: './control.scss',
})
export class Control {}
